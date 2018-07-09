package bloop.engine.tasks

import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture

import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.engine.{Dag, ExecutionContext, Leaf, Parent, State}
import bloop.io.AbsolutePath
import bloop.logging.{BspLogger, Logger}
import bloop.reporter.{BspReporter, LogReporter, Problem, ReporterConfig}
import bloop.{CompileInputs, Compiler, Project}
import monix.eval.Task
import bloop.monix.Java8Compat.{JavaCompletableFutureUtils, ScalaFutureUtils}
import xsbti.compile.{ClasspathOptions, ClasspathOptionsUtil, CompileOrder, PreviousResult}

import scala.util.{Failure, Success, Try}

object Pipelined {
  private val dateFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
  private def currentTime: String = dateFormat.format(new java.util.Date())

  private type IntermediateResult = (Try[URI], Task[Compiler.Result])
  private type ICompileResult = (Project, IntermediateResult)
  private type CompileResult = (Project, Compiler.Result)
  private type CompileTask = Task[Dag[ICompileResult]]

  import scalaz.Show
  private final implicit val showCompileResult: Show[CompileResult] = new Show[CompileResult] {
    private def seconds(ms: Double): String = s"${ms}ms"
    override def shows(r: CompileResult): String = {
      val project = r._1
      r._2 match {
        case Compiler.Result.Empty => s"${project.name} (empty)"
        case Compiler.Result.Cancelled(ms) => s"${project.name} (cancelled, lasted ${ms}ms)"
        case Compiler.Result.Success(_, _, ms) => s"${project.name} (success ${ms}ms)"
        case Compiler.Result.Blocked(on) => s"${project.name} (blocked on ${on.mkString(", ")})"
        case Compiler.Result.Failed(problems, t, ms) =>
          val extra = t match {
            case Some(t) => s"exception '${t.getMessage}', "
            case None => ""
          }
          s"${project.name} (failed with ${Problem.count(problems)}, $extra${ms}ms)"
      }
    }
  }

  case class PipelineInputs(
      project: Project,
      picklepath: List[URI],
      pickleReady: CompletableFuture[URI],
      javaReady: Task[Boolean]
  )

  private object FailPromise extends RuntimeException("Promise completed after compilation error")
  private object CompletePromise extends RuntimeException("Promise completed after compilation")
  private object BlockURI extends RuntimeException("URI cannot complete: compilation is blocked")

  private val startTimings = new scala.collection.mutable.HashMap[Project, Long]
  private val endTimings = new scala.collection.mutable.HashMap[Project, Long]
  private val timingDeps = new scala.collection.mutable.HashMap[Project, List[Project]]
  private val pickleTimings = new scala.collection.mutable.HashMap[Project, Long]

  /**
   * Performs incremental compilation of the dependencies of `project`, including `project` if
   * `excludeRoot` is `false`, excluding it otherwise.
   *
   * @param state          The current state of Bloop.
   * @param project        The project to compile.
   * @param reporterConfig Configuration of the compilation messages reporter.
   * @param excludeRoot    If `true`, compile only the dependencies of `project`. Otherwise,
   *                       also compile `project`.
   * @return The new state of Bloop after compilation.
   */
  def compile(
      state: State,
      project: Project,
      reporterConfig: ReporterConfig,
      sequentialCompilation: Boolean,
      excludeRoot: Boolean = false
  ): Task[State] = {
    import state.{build, logger, compilerCache}
    def toInputs(
        inputs: PipelineInputs,
        config: ReporterConfig,
        result: PreviousResult
    ) = {
      def pruneClasspath(project: Project): Array[AbsolutePath] = {
        val toPrune = build.projects.map(_.classesDir).toSet
        project.classpath.filter(p => !toPrune.contains(p))
      }

      val project = inputs.project
      val instance = project.scalaInstance
      val sources = project.sources.toArray
      val classpath = project.classpath
      val picklepath = inputs.picklepath
      val classesDir = project.classesDir
      val target = project.out
      val scalacOptions = project.scalacOptions.toArray
      val javacOptions = project.javacOptions.toArray
      val cwd = state.build.origin.getParent
      val pickleReady = inputs.pickleReady
      val javaReady = inputs.javaReady

      val classpathOptions = project.classpathOptions
      val compileOrder = project.compileOrder

      // Set the reporter based on the kind of logger to publish diagnostics
      val reporter = logger match {
        case bspLogger: BspLogger =>
          // Don't show errors in reverse order, log as they come!
          new BspReporter(project, bspLogger, cwd, identity, config.copy(reverseOrder = false))
        case _ => new LogReporter(logger, cwd, identity, config)
      }

      // FORMAT: OFF
      CompileInputs(instance, compilerCache, sources, classpath, picklepath.toArray, classesDir, target, scalacOptions, javacOptions, compileOrder, classpathOptions, result, reporter, Some(pickleReady), javaReady, logger)
      // FORMAT: ON
    }

    def exceptions(results: List[CompileResult]): List[(Project, Throwable)] = {
      results.collect { case (p, Compiler.Result.Failed(_, Some(t), _)) => (p, t) }
    }

    def failed(results: List[CompileResult]): List[Project] = {
      results.collect { case (p, Compiler.Result.NotOk(_)) => p }
    }

    def compile(inputs: PipelineInputs): Task[Compiler.Result] = {
      val project = inputs.project
      logger.debug(s"Scheduled compilation of '$project' starting at $currentTime.")
      startTimings += (project -> System.currentTimeMillis())
      val previous = state.results.lastSuccessfulResult(project)
      Compiler.compile(toInputs(inputs, reporterConfig, previous)).map { result =>
        // Do some book-keeping before returning the result to the caller
        endTimings += (project -> System.currentTimeMillis())
        if (!inputs.pickleReady.isDone) {
          result match {
            case Compiler.Result.NotOk(_) =>
              inputs.pickleReady.completeExceptionally(FailPromise)
            case result =>
              logger.warn(s"The project ${project.name} didn't use pipelined compilation.")
              inputs.pickleReady.completeExceptionally(CompletePromise)
          }
        }

        result
      }
    }

    val dag = state.build.getDagFor(project)
    def triggerCompile: Task[State] = {
      toCompileTask(dag, compile(_), logger).flatMap { partialResults0 =>
        val partialResults = Dag.dfs(partialResults0)
        val futureFullResults = Task.gatherUnordered(
          partialResults.map { case (p, (_, futureResult)) => futureResult.map(res => (p, res)) }
        )

        futureFullResults.map { results =>
          val failures = failed(results).distinct
          val newState = state.copy(results = state.results.addResults(results))
          if (failures.isEmpty) {
            startTimings.map {
              case (k, startMs) =>
                val endMs = endTimings.get(k).get
                val allTimingDeps = timingDeps.get(k).get.map(d => endTimings.get(d).get)
                val hypotheticalEnd = if (allTimingDeps.isEmpty) startMs else allTimingDeps.max
                val duration = (endMs - startMs)
                val saved = (hypotheticalEnd - startMs)
                logger.info(s"Project ${k.name} compiled in ${duration}ms; and saved ${saved}ms")
            }
            newState.copy(status = ExitStatus.Ok)
          } else {
            exceptions(results).foreach {
              case (p, t) =>
                logger.error(s"Unexpected error when compiling ${p.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                logger.trace(t)
            }

            failures.foreach(p => logger.error(s"'${p.name}' failed to compile."))
            newState.copy(status = ExitStatus.CompilationError)
          }
        }
      }
    }

    if (!sequentialCompilation) triggerCompile
    else {
      // Check dependent projects didn't fail in previous sequential compile
      val allDependencies = Dag.dfs(dag).toSet
      val dependentResults =
        state.results.allResults.filter(pr => allDependencies.contains(pr._1))
      val failedDependentProjects = failed(dependentResults.toList)
      if (!failedDependentProjects.isEmpty) {
        val failedProjects = failedDependentProjects.map(p => s"'${p.name}'").mkString(", ")
        logger.warn(
          s"Skipping compilation of project '$project'; dependent $failedProjects failed to compile.")
        Task.now(state.copy(status = ExitStatus.CompilationError))
      } else triggerCompile
    }
  }

  /**
   * Turns a dag of projects into a task that returns a dag of compilation results
   * that can then be used to debug the evaluation of the compilation within Monix
   * and access the compilation results received from Zinc.
   *
   * @param dag The dag of projects to be compiled.
   * @return A task that returns a dag of compilation results.
   */
  private def toCompileTask(
      dag: Dag[Project],
      compile: PipelineInputs => Task[Compiler.Result],
      logger: Logger
  ): CompileTask = {
    val tasks = new scala.collection.mutable.HashMap[Dag[Project], CompileTask]()
    def register(k: Dag[Project], v: CompileTask): CompileTask = { tasks.put(k, v); v }

    def blockedBy(dag: Dag[ICompileResult]): Option[Project] = {
      dag match {
        case Leaf((_, (_: Success[_], _))) => None
        case Leaf((_, ((Failure(CompletePromise) | Success(_)), _))) => None
        case Leaf((project, _)) => Some(project)
        case Parent((_, ((Failure(CompletePromise) | Success(_)), _)), _) => None
        case Parent((project, _), _) => Some(project)
      }
    }

    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) =>
              Task.now(new CompletableFuture[URI]()).flatMap { cf =>
                val t = compile(PipelineInputs(project, Nil, cf, Task.now(true)))
                val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                timingDeps += (project -> Nil)
                Task
                  .deferFutureAction(c => cf.asScala(c))
                  .materialize
                  .map(u => Leaf((project, (u, Task.fromFuture(running)))))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { results =>
                val failed = results.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  // No need to sort in topological order -- no clash of symbol can happen.
                  val dfss = results.map(Dag.dfs(_)).flatten
                  val picklepath = dfss.map(_._2._1.toOption).flatten.distinct

                  if (logger.isVerbose) {
                    val pickleProjects = dfss.map(_._1).distinct
                    logger.debug(s"The picklepath for ${project.name} is:\n${picklepath
                      .zip(pickleProjects)
                      .map(c => s"${c._2} ${c._1}")
                      .mkString("  -> ", "\n  -> ", "\n")}")
                  }

                  Task.now(new CompletableFuture[URI]()).flatMap { cf =>
                    // Signals whether Java compilation can proceed or not.
                    val javaReady = {
                      Task
                        .gatherUnordered(dfss.map(t => t._2._2.map(r => t._1 -> r)))
                        .map { rs =>
                          rs.collect {
                            case (_, r @ Compiler.Result.NotOk(_)) => r
                            case (_, r @ Compiler.Result.Blocked(_)) => r
                          }.isEmpty
                        }
                    }

                    val pickleProjects = dfss.map(_._1).distinct
                    timingDeps += (project -> pickleProjects)

                    val t = compile(PipelineInputs(project, picklepath, cf, Task.now(true)))
                    val running = t.executeWithFork.runAsync(ExecutionContext.scheduler)
                    Task
                      .deferFutureAction(c => cf.asScala(c))
                      .materialize
                      .map(u => Parent((project, (u, Task.fromFuture(running))), results))
                  }
                } else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  Task.now(Parent((project, (Failure(BlockURI), blocked)), results))
                }
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag)
  }
}
