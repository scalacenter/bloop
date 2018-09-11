package bloop.engine.tasks

import java.net.URI
import java.util.Optional
import java.util.concurrent.CompletableFuture

import bloop.cli.ExitStatus
import bloop.engine.{Dag, ExecutionContext, Leaf, Parent, State}
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.{BspLogger, Logger}
import bloop.reporter.{BspReporter, LogReporter, Problem, ReporterConfig}
import bloop.{CompileInputs, Compiler, Project, ScalaInstance}
import monix.eval.Task
import bloop.monix.Java8Compat.JavaCompletableFutureUtils
import sbt.internal.inc.bloop.{CompileMode, JavaSignal}
import sbt.internal.inc.javac.JavaNoPosition
import sbt.util.InterfaceUtil
import xsbti.Severity
import xsbti.compile.PreviousResult

import scala.util.{Failure, Success, Try}

object Pipelined {
  private val dateFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
  private def currentTime: String = dateFormat.format(new java.util.Date())

  private case class CompileProducts(
      pickleURI: Try[Optional[URI]],
      javaSources: List[String],
      result: Task[Compiler.Result]
  )

  private object CanProceedCompilation {
    def unapply(products: CompileProducts): Boolean = {
      products match {
        case CompileProducts((Failure(CompletePromise) | Success(_)), _, _) => true
        case _ => false
      }
    }
  }

  private type ICompileResult = (Project, CompileProducts)
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
      pickleReady: CompletableFuture[Optional[URI]],
      javaSignal: Task[JavaSignal]
  )

  // A series of non-leaking exceptions that we use internally in the implementation of pipelining
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
      compileMode: CompileMode.ConfigurableMode,
      excludeRoot: Boolean
  ): Task[State] = {
    import state.{logger, compilerCache}
    def toInputs(
        inputs: PipelineInputs,
        instance: ScalaInstance,
        sources: Array[AbsolutePath],
        config: ReporterConfig,
        result: PreviousResult
    ): CompileInputs = {
      val project = inputs.project
      val classpath = project.classpath
      val picklepath = inputs.picklepath
      val classesDir = project.classesDir
      val target = project.out
      val scalacOptions = project.scalacOptions.toArray ++ Array("-Ygenerate-pickles")
      val javacOptions = project.javacOptions.toArray
      val cwd = state.build.origin.getParent
      val pickleReady = inputs.pickleReady

      val classpathOptions = project.classpathOptions
      val compileOrder = project.compileOrder

      // Set the reporter based on the kind of logger to publish diagnostics
      val reporter = logger match {
        case bspLogger: BspLogger =>
          // Don't show errors in reverse order, log as they come!
          new BspReporter(project, bspLogger, cwd, identity, config.copy(reverseOrder = false))
        case _ => new LogReporter(logger, cwd, identity, config)
      }

      val mode = compileMode match {
        case CompileMode.Parallel(batches) =>
          CompileMode.ParallelAndPipelined(batches, pickleReady, inputs.javaSignal)
        case CompileMode.Sequential =>
          CompileMode.Pipelined(pickleReady, inputs.javaSignal)
      }

      // FORMAT: OFF
      CompileInputs(instance, compilerCache, sources, classpath, picklepath.toArray, classesDir, target, scalacOptions, javacOptions, compileOrder, classpathOptions, result, reporter, mode, logger)
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

      def runCompile(instance: ScalaInstance, sources: Array[AbsolutePath]) = {
        Compiler.compile(toInputs(inputs, instance, sources, reporterConfig, previous)).map { result =>
          // Do some book-keeping before returning the result to the caller
          endTimings += (project -> System.currentTimeMillis())

          if (!inputs.pickleReady.isDone) {
            // Complete the pickle future to avoid deadlocks in case something is off
            result match {
              case Compiler.Result.NotOk(_) =>
                inputs.pickleReady.completeExceptionally(FailPromise)
              case result =>
                logger.warn(s"The project ${project.name} didn't use pipelined compilation.")
                inputs.pickleReady.completeExceptionally(CompletePromise)
            }
          } else {
            // Report if the pickle ready was correctly completed by the compiler
            InterfaceUtil.toOption(inputs.pickleReady.get) match {
              case Some(result) =>
                logger.debug(s"Project ${project.name} compiled with pipelined compilation.")
              case None =>
                logger.warn(s"The project ${project.name} didn't use pipelined compilation.")
            }
          }

          result
        }
      }

      val sources = project.sources.distinct
      val javaSources = sources.flatMap(src => Paths.getAllFiles(src, "glob:**.java")).distinct
      val scalaSources = sources.flatMap(src => Paths.getAllFiles(src, "glob:**.scala")).distinct
      val uniqueSources = (javaSources ++ scalaSources).toArray

      def err(msg: String): Problem = Problem(-1, Severity.Error, msg, JavaNoPosition, "")
      project.scalaInstance match {
        case Some(instance) => runCompile(instance, uniqueSources)
        case None =>
          val addScalaConfiguration = err(
            "Add Scala configuration to the project. If that doesn't fix it, report it upstream")
          // Either return empty result or report
          (scalaSources, javaSources) match {
            case (Nil, Nil) => Task.now(Compiler.Result.Empty)
            case (_: List[AbsolutePath], Nil) =>
              // Let's notify users there is no Scala configuration for a project with Scala sources
              val msg =
                s"Failed to compile project '${project.name}': found Scala sources but project is missing Scala configuration."
              Task.now(Compiler.Result.Failed(List(err(msg), addScalaConfiguration), None, 1))
            case (_, _: List[AbsolutePath]) =>
              // If Java sources exist, we cannot compile them without an instance, fail fast!
              val msg =
                s"Failed to compile ${project.name}'s Java sources because the default Scala instance couldn't be created."
              Task.now(Compiler.Result.Failed(List(err(msg), addScalaConfiguration), None, 1))
          }
      }
    }

    val dag = state.build.getDagFor(project)
    def triggerCompile: Task[State] = {
      toCompileTask(dag, compile(_), logger).flatMap { partialResults0 =>
        val partialResults = Dag.dfs(partialResults0)
        val futureFullResults = Task.gatherUnordered(
          partialResults.map {
            case (p, CompileProducts(_, _, futureResult)) => futureResult.map(res => (p, res))
          }
        )

        futureFullResults.map { results =>
          val failures = failed(results).distinct
          val newState = state.copy(results = state.results.addResults(results))
          if (failures.isEmpty) {
            newState.copy(status = ExitStatus.Ok)
          } else {
            exceptions(results).foreach {
              case (p, t) =>
                logger.error(s"Unexpected error when compiling ${p.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                t.printStackTrace()
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
        case Leaf((_, CompileProducts(_: Success[_], _, _))) => None
        case Leaf((_, CanProceedCompilation())) => None
        case Leaf((project, _)) => Some(project)
        case Parent((_, CanProceedCompilation()), _) => None
        case Parent((project, _), _) => Some(project)
      }
    }

    def loop(dag: Dag[Project]): CompileTask = {
      tasks.get(dag) match {
        case Some(task) => task
        case None =>
          val task = dag match {
            case Leaf(project) =>
              Task.now(new CompletableFuture[Optional[URI]]()).flatMap { cf =>
                val t = compile(
                  PipelineInputs(project, Nil, cf, Task.now(JavaSignal.ContinueCompilation)))
                val running = t.executeAsync.runAsync(ExecutionContext.scheduler)
                timingDeps += (project -> Nil)
                Task
                  .deferFutureAction(c => cf.asScala(c))
                  .materialize
                  .map(u => Leaf((project, CompileProducts(u, Nil, Task.fromFuture(running)))))
              }

            case Parent(project, dependencies) =>
              val downstream = dependencies.map(loop)
              Task.gatherUnordered(downstream).flatMap { results =>
                val failed = results.flatMap(dag => blockedBy(dag).toList)
                if (failed.isEmpty) {
                  // No need to sort in topological order -- no clash of symbol can happen.
                  val dfss = results.map(Dag.dfs(_)).flatten
                  val picklepath = dfss
                    .map(_._2.pickleURI.toOption.map(o => InterfaceUtil.toOption(o)).flatten)
                    .flatten
                    .distinct

                  if (logger.isVerbose) {
                    val pickleProjects = dfss.map(_._1).distinct
                    logger.debug(s"The picklepath for ${project.name} is:\n${picklepath
                      .zip(pickleProjects)
                      .map(c => s"${c._2} ${c._1}")
                      .mkString("  -> ", "\n  -> ", "\n")}")
                  }

                  Task.now(new CompletableFuture[Optional[URI]]()).flatMap { cf =>
                    // Signals whether Java compilation can proceed or not.
                    val javaSignal = {
                      Task
                        .gatherUnordered(dfss.map(t => t._2.result.map(r => t._1 -> r)))
                        .map { rs =>
                          val projects = rs.collect { case (p, Compiler.Result.NotOk(_)) => p.name }
                          if (projects.isEmpty) JavaSignal.ContinueCompilation
                          else JavaSignal.FailFastCompilation(projects)
                        }
                    }

                    val pickleProjects = dfss.map(_._1).distinct
                    timingDeps += (project -> pickleProjects)

                    val t = compile(PipelineInputs(project, picklepath, cf, javaSignal))
                    val running = t.executeAsync.runAsync(ExecutionContext.scheduler)
                    val futureRunning = Task.fromFuture(running)
                    Task
                      .deferFutureAction(c => cf.asScala(c))
                      .materialize
                      .map(u => Parent((project, CompileProducts(u, Nil, futureRunning)), results))
                  }
                } else {
                  // Register the name of the projects we're blocked on (intransitively)
                  val blocked = Task.now(Compiler.Result.Blocked(failed.map(_.name)))
                  val blockedResult = CompileProducts(Failure(BlockURI), Nil, blocked)
                  Task.now(Parent((project, blockedResult), results))
                }
              }
          }
          register(dag, task.memoize)
      }
    }

    loop(dag)
  }
}
