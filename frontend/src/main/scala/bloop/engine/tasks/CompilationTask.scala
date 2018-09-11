package bloop.engine.tasks

import bloop.cli.ExitStatus
import bloop.engine._
import bloop.engine.tasks.compilation.{CompileGraph, FinalCompileResult, CompileExceptions}
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.{BspLogger, Logger}
import bloop.reporter._
import bloop.{CompileInputs, Compiler, Project, ScalaInstance}
import monix.eval.Task
import sbt.internal.inc.bloop.CompileMode
import sbt.util.InterfaceUtil

object CompilationTask {
  private val dateFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
  private def currentTime: String = dateFormat.format(new java.util.Date())

  private final val GeneratePicklesFlag = "-Ygenerate-pickles"

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
      pipeline: Boolean,
      excludeRoot: Boolean
  ): Task[State] = {
    val cwd = state.build.origin.getParent
    import state.{logger, compilerCache}
    def compile(graphInputs: CompileGraph.Inputs): Task[Compiler.Result] = {
      val project = graphInputs.project
      sourcesAndInstanceFrom(project) match {
        case Left(earlyResult) => Task.now(earlyResult)
        case Right(SourcesAndInstance(sources, instance)) =>
          val previous = state.results.lastSuccessfulResult(project)
          val reporter = createCompilationReporter(project, cwd, reporterConfig, state.logger)

          val (scalacOptions, mode) = {
            if (!pipeline) ((GeneratePicklesFlag :: project.scalacOptions).toArray, compileMode)
            else {
              val scalacOptions = (GeneratePicklesFlag :: project.scalacOptions).toArray
              val mode = compileMode match {
                case CompileMode.Sequential =>
                  CompileMode.Pipelined(graphInputs.pickleReady, graphInputs.javaSignal)
                case CompileMode.Parallel(batches) =>
                  CompileMode.ParallelAndPipelined(
                    batches,
                    graphInputs.pickleReady,
                    graphInputs.javaSignal
                  )
              }
              (scalacOptions, mode)
            }
          }

          val backendInputs = CompileInputs(
            instance,
            compilerCache,
            sources,
            project.classpath,
            graphInputs.picklepath.toArray,
            project.classesDir,
            project.out,
            scalacOptions,
            project.javacOptions.toArray,
            project.compileOrder,
            project.classpathOptions,
            previous,
            reporter,
            compileMode
          )

          Compiler.compile(backendInputs).map { result =>
            // Do some implementation book-keeping before returning the compilation result
            if (!graphInputs.pickleReady.isDone) {
              /*
               * When pipeline compilation is disabled either by the user or the implementation
               * decides not to use it (in the presence of macros from the same build), we force
               * the completion of the pickle promise to avoid deadlocks.
               */
              result match {
                case Compiler.Result.NotOk(_) =>
                  graphInputs.pickleReady.completeExceptionally(CompileExceptions.FailPromise)
                case result =>
                  if (pipeline)
                    logger.warn(s"The project ${project.name} didn't use pipelined compilation.")
                  graphInputs.pickleReady.completeExceptionally(CompileExceptions.CompletePromise)
              }
            } else {
              // Report if the pickle ready was correctly completed by the compiler
              InterfaceUtil.toOption(graphInputs.pickleReady.get) match {
                case None if pipeline =>
                  logger.warn(s"Project ${project.name} compiled without pipelined compilation.")
                case _ => logger.debug(s"The pickle promise of ${project.name} completed in Zinc.")
              }
            }

            result
          }
      }
    }

    val dag = state.build.getDagFor(project)
    def triggerCompile: Task[State] = {
      CompileGraph.traverse(dag, compile(_), pipeline, logger).flatMap { partialResultDag =>
        val partialResults = Dag.dfs(partialResultDag)
        Task.gatherUnordered(partialResults.map(_.toFinalResult)).map { results =>
          val failures = results.collect {
            case FinalCompileResult(p, Compiler.Result.NotOk(_)) => p
          }

          val newState = state.copy(results = state.results.addFinalResults(results))
          if (failures.isEmpty) newState.copy(status = ExitStatus.Ok)
          else {
            results.foreach {
              case FinalCompileResult(p, Compiler.Result.Failed(_, Some(t), _)) =>
                logger.error(s"Unexpected error when compiling ${p.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                t.printStackTrace()
                logger.trace(t)
              case _ => () // Do nothing when the final compilation result is not an actual error
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
      val failedDependentProjects =
        dependentResults.collect { case (p, Compiler.Result.NotOk(_)) => p }
      if (!failedDependentProjects.isEmpty) {
        val failedProjects = failedDependentProjects.map(p => s"'${p.name}'").mkString(", ")
        logger.warn(
          s"Skipping compilation of project '$project'; dependent $failedProjects failed to compile.")
        Task.now(state.copy(status = ExitStatus.CompilationError))
      } else triggerCompile
    }
  }

  private case class SourcesAndInstance(sources: Array[AbsolutePath], instance: ScalaInstance)
  private def sourcesAndInstanceFrom(
      project: Project
  ): Either[Compiler.Result, SourcesAndInstance] = {
    val sources = project.sources.distinct
    val javaSources = sources.flatMap(src => Paths.getAllFiles(src, "glob:**.java")).distinct
    val scalaSources = sources.flatMap(src => Paths.getAllFiles(src, "glob:**.scala")).distinct
    val uniqueSources = (javaSources ++ scalaSources).toArray

    project.scalaInstance match {
      case Some(instance) => Right(SourcesAndInstance(uniqueSources, instance))
      case None =>
        (scalaSources, javaSources) match {
          case (Nil, Nil) => Left(Compiler.Result.Empty)
          case (_: List[AbsolutePath], Nil) =>
            // Let's notify users there is no Scala configuration for a project with Scala sources
            Left(Compiler.Result.GlobalError(Feedback.missingScalaInstance(project)))
          case (_, _: List[AbsolutePath]) =>
            // If Java sources exist, we cannot compile them without an instance, fail fast!
            Left(Compiler.Result.GlobalError(Feedback.missingInstanceForJavaCompilation(project)))
        }
    }
  }

  private def createCompilationReporter(
      project: Project,
      cwd: AbsolutePath,
      config: ReporterConfig,
      logger: Logger
  ): Reporter = {
    logger match {
      case bspLogger: BspLogger =>
        // Disable reverse order to show errors as they come for BSP clients
        new BspReporter(project, bspLogger, cwd, identity, config.copy(reverseOrder = false))
      case _ => new LogReporter(logger, cwd, identity, config)
    }
  }
}
