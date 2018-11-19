package bloop.engine.tasks

import bloop.cli.ExitStatus
import bloop.data.Project
import bloop.engine._
import bloop.engine.tasks.compilation._
import bloop.io.AbsolutePath
import bloop.logging.{BspServerLogger, DebugFilter, Logger}
import bloop.reporter._
import bloop.{CompileInputs, CompileMode, Compiler}
import monix.eval.Task
import sbt.internal.inc.AnalyzingCompiler

object CompilationTask {
  private implicit val logContext: DebugFilter = DebugFilter.Compilation
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
      userCompileMode: CompileMode.ConfigurableMode,
      pipeline: Boolean,
      excludeRoot: Boolean
  ): Task[State] = {
    val cwd = state.build.origin.getParent
    import state.{logger, compilerCache}
    def compile(graphInputs: CompileGraph.Inputs): Task[Compiler.Result] = {
      val project = graphInputs.bundle.project
      graphInputs.bundle.toSourcesAndInstance match {
        case Left(earlyResult) =>
          val complete = CompileExceptions.CompletePromise(graphInputs.store)
          graphInputs.irPromise.completeExceptionally(complete)
          graphInputs.completeJava.complete(())
          Task.now(earlyResult)
        case Right(CompileSourcesAndInstance(sources, instance, javaOnly)) =>
          val previousResult = state.results.latestResult(project)
          val previousSuccesful = state.results.lastSuccessfulResultOrEmpty(project)
          val reporter = createCompilationReporter(project, cwd, reporterConfig, state.logger)

          val (scalacOptions, compileMode) = {
            if (!pipeline) (project.scalacOptions.toArray, userCompileMode)
            else {

              val scalacOptions = (GeneratePicklesFlag :: project.scalacOptions).toArray
              val mode = userCompileMode match {
                case CompileMode.Sequential =>
                  CompileMode.Pipelined(
                    graphInputs.irPromise,
                    graphInputs.completeJava,
                    graphInputs.transitiveJavaSignal,
                    graphInputs.oracle,
                    graphInputs.separateJavaAndScala
                  )
                case CompileMode.Parallel(batches) =>
                  CompileMode.ParallelAndPipelined(
                    batches,
                    graphInputs.irPromise,
                    graphInputs.completeJava,
                    graphInputs.transitiveJavaSignal,
                    graphInputs.oracle,
                    graphInputs.separateJavaAndScala
                  )
              }
              (scalacOptions, mode)
            }
          }

          val backendInputs = CompileInputs(
            instance,
            compilerCache,
            sources.toArray,
            project.classpath,
            graphInputs.store,
            project.classesDir,
            project.out,
            scalacOptions,
            project.javacOptions.toArray,
            project.compileOrder,
            project.classpathOptions,
            previousSuccesful,
            previousResult,
            reporter,
            compileMode,
            graphInputs.dependentResults
          )

          Compiler.compile(backendInputs).map { result =>
            // Do some implementation book-keeping before returning the compilation result
            if (!graphInputs.irPromise.isDone) {
              /*
               * When pipeline compilation is disabled either by the user or the implementation
               * decides not to use it (in the presence of macros from the same build), we force
               * the completion of the pickle promise to avoid deadlocks.
               */
              result match {
                case Compiler.Result.NotOk(_) =>
                  graphInputs.irPromise.completeExceptionally(CompileExceptions.FailPromise)
                case result =>
                  if (pipeline && !javaOnly)
                    logger.warn(s"The project ${project.name} didn't use pipelined compilation.")
                  val complete = CompileExceptions.CompletePromise(graphInputs.store)
                  graphInputs.irPromise.completeExceptionally(complete)
              }
            } else {
              // Report if the pickle ready was correctly completed by the compiler
              graphInputs.irPromise.get match {
                case Array() if pipeline =>
                  logger.warn(s"Project ${project.name} compiled without pipelined compilation.")
                case _ => logger.debug(s"The pickle promise of ${project.name} completed in Zinc.")
              }
            }

            result
          }
      }
    }

    def setup(project: Project): CompileBundle = CompileBundle(project)
    val dag = state.build.getDagFor(project)
    def triggerCompile: Task[State] = {
      CompileGraph.traverse(dag, setup(_), compile(_), pipeline, logger).flatMap { partialDag =>
        val partialResults = Dag.dfs(partialDag)
        Task.gatherUnordered(partialResults.map(_.toFinalResult)).map { results =>
          val failures = results.collect {
            case FinalCompileResult(p, Compiler.Result.NotOk(_), _) => p
          }

          cleanStatePerBuildRun(results, state)
          val newState = state.copy(results = state.results.addFinalResults(results))
          if (failures.isEmpty) newState.copy(status = ExitStatus.Ok)
          else {
            results.foreach {
              case FinalCompileResult(bundle, Compiler.Result.Failed(_, Some(t), _), _) =>
                val project = bundle.project
                logger.error(s"Unexpected error when compiling ${project.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                t.printStackTrace()
                logger.trace(t)
              case _ => () // Do nothing when the final compilation result is not an actual error
            }

            failures.foreach(b => logger.error(s"'${b.project.name}' failed to compile."))
            newState.copy(status = ExitStatus.CompilationError)
          }
        }
      }
    }

    if (!sequentialCompilation) triggerCompile
    else {
      // Check dependent projects didn't fail in previous sequential compile
      val allDependencies = Dag.dfs(dag).toSet
      val dependentResults = state.results.allResults.filter(pr => allDependencies.contains(pr._1))
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

  // Running this method takes around 10ms per compiler and it's rare to have more than one to reset!
  private def cleanStatePerBuildRun(results: List[FinalCompileResult], state: State): Unit = {
    import xsbti.compile.{IRStore, EmptyIRStore}
    import java.nio.file.Files
    val tmpDir = Files.createTempDirectory("outputDir")
    try {
      // Merge IRs of all stores (no matter if they were generated by different Scala instances)
      val mergedStore = results.foldLeft(EmptyIRStore.getStore: IRStore) {
        case (acc, result) => acc.merge(result.store)
      }

      val instances = results.iterator.flatMap(_.bundle.project.scalaInstance.toIterator).toSet
      instances.foreach { i =>
          // Initialize a compiler so that we can reset the global state after a build compilation
          val logger = state.logger
          val scalac = state.compilerCache.get(i).scalac().asInstanceOf[AnalyzingCompiler]
          val config = ReporterConfig.defaultFormat
          val cwd = state.commonOptions.workingPath
          val reporter = new LogReporter(logger, cwd, identity, config)
          val output = new sbt.internal.inc.ConcreteSingleOutput(tmpDir.toFile)
          val cached = scalac.newCachedCompiler(Array.empty[String], output, logger, reporter)
          // Reset the global ir caches on the cached compiler only for the store IRs
          scalac.resetGlobalIRCaches(mergedStore, cached, logger)
      }
    } finally {
      Files.delete(tmpDir)
    }
  }

  private def createCompilationReporter(
      project: Project,
      cwd: AbsolutePath,
      config: ReporterConfig,
      logger: Logger
  ): Reporter = {
    logger match {
      case bspLogger: BspServerLogger =>
        // Disable reverse order to show errors as they come for BSP clients
        new BspProjectReporter(project, bspLogger, cwd, identity, config.copy(reverseOrder = false))
      case _ => new LogReporter(logger, cwd, identity, config)
    }
  }
}
