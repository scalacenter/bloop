package bloop.engine.tasks

import bloop.{CompileInputs, CompileMode, Compiler, CompileOutPaths}
import bloop.cli.ExitStatus
import bloop.data.Project
import bloop.engine.caches.{ResultsCache, LastSuccessfulResult}
import bloop.engine._
import bloop.engine.tasks.compilation.{FinalCompileResult, _}
import bloop.io.AbsolutePath
import bloop.tracing.BraveTracer
import bloop.logging.{BspServerLogger, DebugFilter, Logger, ObservedLogger, LoggerAction}
import bloop.reporter.{
  Reporter,
  ReporterAction,
  ReporterConfig,
  ReporterInputs,
  LogReporter,
  ObservedReporter
}

import java.nio.file.Files
import java.util.UUID

import monix.eval.Task
import monix.reactive.{Observer, Observable, MulticastStrategy}

import sbt.internal.inc.AnalyzingCompiler
import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup}

import scala.concurrent.Promise

object CompileTask {
  private implicit val logContext: DebugFilter = DebugFilter.Compilation
  private val dateFormat = new java.text.SimpleDateFormat("HH:mm:ss.SSS")
  private def currentTime: String = dateFormat.format(new java.util.Date())

  private final val GeneratePicklesFlag = "-Ygenerate-pickles"

  /**
   * Performs incremental compilation of the dependencies of `project`, including `project` if
   * `excludeRoot` is `false`, excluding it otherwise.
   *
   * @param state             The current state of Bloop.
   * @param dag               The project dag to compile.
   * @param createReporter    A function that creates a per-project compilation reporter.
   * @param pipeline          Enable build pipelining for this compilation run.
   * @param excludeRoot       Compile only the dependencies of `project` (required by `console`).
   * @param cancelCompilation A promise that is completed when the user cancels compilation.
   *
   * @return The new state of Bloop after compilation.
   */
  def compile[UseSiteLogger <: Logger](
      state: State,
      dag: Dag[Project],
      createReporter: ReporterInputs[UseSiteLogger] => Reporter,
      userCompileMode: CompileMode.ConfigurableMode,
      pipeline: Boolean,
      excludeRoot: Boolean,
      cancelCompilation: Promise[Unit],
      rawLogger: UseSiteLogger
  ): Task[State] = {
    import bloop.internal.build.BuildInfo
    val originUri = state.build.origin
    val cwd = originUri.getParent
    val topLevelTargets = Dag.directDependencies(List(dag)).mkString(", ")
    val topLevelTracer = BraveTracer(
      s"compile $topLevelTargets (transitively)",
      "bloop.version" -> BuildInfo.version,
      "zinc.version" -> BuildInfo.zincVersion,
      "build.uri" -> originUri.syntax,
      "compile.target" -> topLevelTargets
    )

    def compile(graphInputs: CompileGraph.Inputs): Task[Compiler.Result] = {
      val bundle = graphInputs.bundle
      val project = bundle.project
      val logger = bundle.logger
      val reporter = bundle.reporter
      val compileProjectTracer = topLevelTracer.startNewChildTracer(
        s"compile ${project.name}",
        "compile.target" -> project.name
      )

      bundle.toSourcesAndInstance match {
        case Left(earlyResult) =>
          val complete = CompileExceptions.CompletePromise(graphInputs.store)
          graphInputs.irPromise.completeExceptionally(complete)
          graphInputs.completeJava.complete(())
          compileProjectTracer.terminate()
          Task.now(earlyResult)
        case Right(CompileSourcesAndInstance(sources, instance, javaOnly)) =>
          val (previousResult, previousSuccessful) = {
            if (pipeline) {
              // Disable incremental compilation if pipelining is enabled
              Compiler.Result.Empty -> LastSuccessfulResult.Empty
            } else {
              state.results.latestResult(project) ->
                state.results.lastSuccessfulResultOrEmpty(project)
            }
          }

          val readOnlyClassesDir = previousSuccessful.classesDir
          val externalUserClassesDir = state.client.getUniqueClassesDirFor(project)
          val compileOut = CompileOutPaths(
            project.analysisOut,
            externalUserClassesDir,
            readOnlyClassesDir
          )

          val newClassesDir = compileOut.internalNewClassesDir
          val classpath = bundle.dependenciesData.buildFullCompileClasspathFor(
            project,
            readOnlyClassesDir,
            newClassesDir
          )

          // Warn user if detected missing dep, see https://github.com/scalacenter/bloop/issues/708
          state.build.hasMissingDependencies(project).foreach { missing =>
            Feedback
              .detectMissingDependencies(project, missing)
              .foreach(msg => logger.warn(msg))
          }

          val (scalacOptions, compileMode) = {
            if (!pipeline) (project.scalacOptions, userCompileMode)
            else {
              val scalacOptions = (GeneratePicklesFlag :: project.scalacOptions)
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

          val inputs = CompilerPluginWhitelist
            .enablePluginCaching(instance.version, scalacOptions, logger, compileProjectTracer)
            .executeOn(ExecutionContext.ioScheduler)
            .map { scalacOptions =>
              CompileInputs(
                instance,
                state.compilerCache,
                sources.toArray,
                classpath,
                bundle.oracleInputs,
                graphInputs.store,
                compileOut,
                project.out,
                scalacOptions.toArray,
                project.javacOptions.toArray,
                project.compileOrder,
                project.classpathOptions,
                previousSuccessful.previous,
                previousResult,
                reporter,
                logger,
                compileMode,
                graphInputs.dependentResults,
                cancelCompilation,
                compileProjectTracer,
                ExecutionContext.ioScheduler,
                ExecutionContext.ioExecutor
              )
            }

          inputs.flatMap(inputs => Compiler.compile(inputs)).map { result =>
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

            // Finish the tracer as soon as possible
            compileProjectTracer.terminate()

            result
          }
      }
    }

    def setup(inputs: CompileGraph.BundleInputs): Task[CompileBundle] = {
      // Create a multicast observable stream to allow multiple mirrors of loggers
      val (observer, observable) = {
        Observable.multicast[Either[ReporterAction, LoggerAction]](
          MulticastStrategy.replay
        )(ExecutionContext.ioScheduler)
      }

      val logger = ObservedLogger(rawLogger, observer)
      val underlying = createReporter(ReporterInputs(inputs.project, cwd, rawLogger))
      val reporter = new ObservedReporter(logger, underlying)
      CompileBundle.computeFrom(inputs, reporter, logger, observable, topLevelTracer)
    }

    CompileGraph.traverse(dag, setup(_), compile(_), pipeline).flatMap { partialDag =>
      val partialResults = Dag.dfs(partialDag)
      val finalResults = partialResults.map(r => PartialCompileResult.toFinalResult(r))
      Task.gatherUnordered(finalResults).map(_.flatten).flatMap { results =>
        cleanStatePerBuildRun(dag, results, state)
        val stateWithResults = state.copy(results = state.results.addFinalResults(results))
        val failures = results.collect {
          case FinalNormalCompileResult(p, Compiler.Result.NotOk(_), _) => p
        }

        val newState: State = {
          if (failures.isEmpty) {
            stateWithResults.copy(status = ExitStatus.Ok)
          } else {
            results.foreach {
              case FinalNormalCompileResult(project, Compiler.Result.Failed(_, Some(t), _, _), _) =>
                rawLogger
                  .error(s"Unexpected error when compiling ${project.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                t.printStackTrace()
                rawLogger.trace(t)
              case _ => () // Do nothing when the final compilation result is not an actual error
            }

            failures.foreach(p => rawLogger.error(s"'${p.name}' failed to compile."))
            stateWithResults.copy(status = ExitStatus.CompilationError)
          }
        }

        // Collect the background tasks from successful compilations
        val backgroundTasks = Task.gatherUnordered {
          results.collect {
            case FinalNormalCompileResult(_, success: Compiler.Result.Success, _) =>
              Task.fromFuture(success.backgroundTasks)
            case FinalNormalCompileResult(_, failure: Compiler.Result.Failed, _) =>
              Task.fromFuture(failure.backgroundTasks)
            case FinalNormalCompileResult(_, cancelled: Compiler.Result.Cancelled, _) =>
              Task.fromFuture(cancelled.backgroundTasks)
          }
        }

        backgroundTasks.map { _ =>
          // Terminate the tracer after we've blocked on the background tasks
          topLevelTracer.terminate()
          newState
        }
      }
    }
  }

  // Running this method takes around 10ms per compiler and it's rare to have more than one to reset!
  private def cleanStatePerBuildRun(
      dag: Dag[Project],
      results: List[FinalCompileResult],
      state: State
  ): Unit = {
    import xsbti.compile.{IRStore, EmptyIRStore}
    val tmpDir = Files.createTempDirectory("outputDir")
    try {
      // Merge IRs of all stores (no matter if they were generated by different Scala instances)
      val mergedStore = results.foldLeft(EmptyIRStore.getStore: IRStore) {
        case (acc, result) => acc.merge(result.store)
      }

      val instances = results.iterator.flatMap {
        case FinalNormalCompileResult(project, _, _) => project.scalaInstance
        case FinalEmptyResult => Nil
      }.toSet

      instances.foreach { i =>
        // Initialize a compiler so that we can reset the global state after a build compilation
        val scalac = state.compilerCache.get(i).scalac().asInstanceOf[AnalyzingCompiler]
        val config = ReporterConfig.defaultFormat
        val cwd = state.commonOptions.workingPath
        val randomProjectFromDag = Dag.dfs(dag).head
        val logger = ObservedLogger.dummy(state.logger, ExecutionContext.ioScheduler)
        val reporter = new LogReporter(randomProjectFromDag, logger, cwd, config)
        val output = new sbt.internal.inc.ConcreteSingleOutput(tmpDir.toFile)
        val cached = scalac.newCachedCompiler(Array.empty[String], output, logger, reporter)
        // Reset the global ir caches on the cached compiler only for the store IRs
        scalac.resetGlobalIRCaches(mergedStore, cached, logger)
      }
    } finally {
      Files.delete(tmpDir)
    }
  }
}
