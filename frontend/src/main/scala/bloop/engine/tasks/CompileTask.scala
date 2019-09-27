package bloop.engine.tasks

import bloop.{
  CompileInputs,
  CompileMode,
  Compiler,
  CompileOutPaths,
  CompileProducts,
  CompileBackgroundTasks,
  CompileExceptions
}
import bloop.io.{Paths => BloopPaths}
import bloop.cli.ExitStatus
import bloop.data.Project
import bloop.engine.{State, Dag, ExecutionContext, Feedback}
import bloop.engine.caches.{ResultsCache, LastSuccessfulResult}
import bloop.engine.tasks.compilation.{FinalCompileResult, _}
import bloop.io.{AbsolutePath, ParallelOps}
import bloop.io.ParallelOps.CopyMode
import bloop.tracing.BraveTracer
import bloop.logging.{DebugFilter, Logger, ObservedLogger, LoggerAction}
import bloop.reporter.{
  Reporter,
  ReporterAction,
  ReporterConfig,
  ReporterInputs,
  LogReporter,
  ObservedReporter
}

import java.util.UUID
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

import monix.eval.Task
import monix.execution.CancelableFuture
import monix.reactive.{Observer, Observable, MulticastStrategy}

import sbt.internal.inc.AnalyzingCompiler
import xsbti.compile.{PreviousResult, CompileAnalysis, MiniSetup}

import scala.concurrent.Promise
import bloop.io.Paths
import scala.collection.mutable
import bloop.Compiler.Result.GlobalError
import bloop.Compiler.Result.Empty
import bloop.Compiler.Result.Success
import bloop.Compiler.Result.Failed
import bloop.Compiler.Result.Cancelled
import bloop.Compiler.Result.Blocked

object CompileTask {
  private implicit val logContext: DebugFilter = DebugFilter.Compilation
  def compile[UseSiteLogger <: Logger](
      state: State,
      dag: Dag[Project],
      createReporter: ReporterInputs[UseSiteLogger] => Reporter,
      pipeline: Boolean,
      excludeRoot: Boolean,
      cancelCompilation: Promise[Unit],
      rawLogger: UseSiteLogger
  ): Task[State] = {
    import bloop.data.ClientInfo
    import bloop.internal.build.BuildInfo
    val originUri = state.build.origin
    val cwd = originUri.getParent
    val topLevelTargets = Dag.directDependencies(List(dag)).mkString(", ")
    val clientName = state.client match {
      case cliClient: ClientInfo.CliClientInfo => cliClient.id
      case bspClient: ClientInfo.BspClientInfo => bspClient.uniqueId
    }

    val rootTracer = BraveTracer(
      s"compile $topLevelTargets (transitively)",
      "bloop.version" -> BuildInfo.version,
      "zinc.version" -> BuildInfo.zincVersion,
      "build.uri" -> originUri.syntax,
      "compile.target" -> topLevelTargets,
      "client" -> clientName
    )

    val bgTracer = rootTracer.toIndependentTracer(
      s"background IO work after compiling $topLevelTargets (transitively)",
      "bloop.version" -> BuildInfo.version,
      "zinc.version" -> BuildInfo.zincVersion,
      "build.uri" -> originUri.syntax,
      "compile.target" -> topLevelTargets,
      "client" -> clientName
    )

    def compile(graphInputs: CompileGraph.Inputs): Task[ResultBundle] = {
      val bundle = graphInputs.bundle
      val project = bundle.project
      val logger = bundle.logger
      val reporter = bundle.reporter
      val previousResult = bundle.latestResult
      val compileOut = bundle.out
      val lastSuccessful = bundle.lastSuccessful
      val compileProjectTracer = rootTracer.startNewChildTracer(
        s"compile ${project.name}",
        "compile.target" -> project.name
      )

      bundle.prepareSourcesAndInstance match {
        case Left(earlyResultBundle) =>
          graphInputs.pipelineInputs match {
            case None => ()
            case Some(inputs) =>
              inputs.irPromise.trySuccess(new Array(0))
              inputs.finishedCompilation.trySuccess(None)
              inputs.completeJava.trySuccess(())
          }
          compileProjectTracer.terminate()
          Task.now(earlyResultBundle)
        case Right(CompileSourcesAndInstance(sources, instance, javaOnly)) =>
          val externalUserClassesDir = bundle.clientClassesDir
          val readOnlyClassesDir = lastSuccessful.classesDir
          val newClassesDir = compileOut.internalNewClassesDir
          val classpath = bundle.dependenciesData.buildFullCompileClasspathFor(
            project,
            readOnlyClassesDir,
            newClassesDir
          )

          // Warn user if detected missing dep, see https://github.com/scalacenter/bloop/issues/708
          state.build.hasMissingDependencies(project).foreach { missing =>
            Feedback
              .detectMissingDependencies(project.name, missing)
              .foreach(msg => logger.warn(msg))
          }

          val configuration = configureCompilation(project, graphInputs, compileOut)
          val newScalacOptions = {
            CompilerPluginWhitelist
              .enableCachingInScalacOptions(
                instance.version,
                configuration.scalacOptions,
                logger,
                compileProjectTracer,
                5
              )
          }

          val inputs = newScalacOptions.map { newScalacOptions =>
            CompileInputs(
              instance,
              state.compilerCache,
              sources.toArray,
              classpath,
              bundle.uniqueInputs,
              compileOut,
              project.out,
              newScalacOptions.toArray,
              project.javacOptions.toArray,
              project.compileOrder,
              project.classpathOptions,
              lastSuccessful.previous,
              previousResult,
              reporter,
              logger,
              configuration.mode,
              graphInputs.dependentResults,
              cancelCompilation,
              compileProjectTracer,
              ExecutionContext.ioScheduler,
              ExecutionContext.ioExecutor,
              bundle.dependenciesData.allInvalidatedClassFiles,
              bundle.dependenciesData.allGeneratedClassFilePaths
            )
          }

          val waitOnReadClassesDir = {
            compileProjectTracer.traceTask("wait on populating products") { _ =>
              // This task is memoized and started by the compilation that created
              // it, so this execution blocks until it's run or completes right away
              lastSuccessful.populatingProducts
            }
          }

          // Block on the task associated with this result that sets up the read-only classes dir
          waitOnReadClassesDir.flatMap { _ =>
            // Only when the task is finished, we kickstart the compilation
            inputs.flatMap(inputs => Compiler.compile(inputs)).map { result =>
              // Post-compilation hook to complete/validate pipelining state
              runPipeliningBookkeeping(graphInputs, result, javaOnly, logger)

              def runPostCompilationTasks(
                  backgroundTasks: CompileBackgroundTasks
              ): CancelableFuture[Unit] = {
                // Post compilation tasks use tracer, so terminate right after they have
                val postCompilationTasks =
                  backgroundTasks
                    .trigger(externalUserClassesDir, compileProjectTracer)
                    .doOnFinish(_ => Task(compileProjectTracer.terminate()))
                postCompilationTasks.runAsync(ExecutionContext.ioScheduler)
              }

              // Populate the last successful result if result was success
              result match {
                case s: Compiler.Result.Success =>
                  val runningTasks = runPostCompilationTasks(s.backgroundTasks)
                  val blockingOnRunningTasks = Task
                    .fromFuture(runningTasks)
                    .executeOn(ExecutionContext.ioScheduler)
                  val populatingTask = {
                    if (s.isNoOp) blockingOnRunningTasks //Task.unit
                    else {
                      for {
                        _ <- blockingOnRunningTasks
                        _ <- populateNewReadOnlyClassesDir(s.products, bgTracer, rawLogger)
                          .doOnFinish(_ => Task(bgTracer.terminate()))
                      } yield ()
                    }
                  }

                  // Memoize so that no matter how many times it's run, it's executed only once
                  val newSuccessful =
                    LastSuccessfulResult(bundle.uniqueInputs, s.products, populatingTask.memoize)
                  ResultBundle(s, Some(newSuccessful), Some(lastSuccessful), runningTasks)
                case f: Compiler.Result.Failed =>
                  val runningTasks = runPostCompilationTasks(f.backgroundTasks)
                  ResultBundle(result, None, Some(lastSuccessful), runningTasks)
                case c: Compiler.Result.Cancelled =>
                  val runningTasks = runPostCompilationTasks(c.backgroundTasks)
                  ResultBundle(result, None, Some(lastSuccessful), runningTasks)
                case _: Compiler.Result.Blocked | Compiler.Result.Empty |
                    _: Compiler.Result.GlobalError =>
                  ResultBundle(result, None, None, CancelableFuture.unit)
              }
            }
          }
      }
    }

    def setup(inputs: CompileDefinitions.BundleInputs): Task[CompileBundle] = {
      // Create a multicast observable stream to allow multiple mirrors of loggers
      val (observer, obs) = {
        Observable.multicast[Either[ReporterAction, LoggerAction]](
          MulticastStrategy.replay
        )(ExecutionContext.ioScheduler)
      }

      // Compute the previous and last successful results from the results cache
      import inputs.project
      val (prev, last) = {
        if (pipeline) {
          val emptySuccessful = LastSuccessfulResult.empty(project)
          // Disable incremental compilation if pipelining is enabled
          Compiler.Result.Empty -> emptySuccessful
        } else {
          // Use last successful from user cache, only useful if this is its first
          // compilation, otherwise we use the last successful from [[CompileGraph]]
          val latestResult = state.results.latestResult(project)
          val lastSuccessful = state.results.lastSuccessfulResultOrEmpty(project)
          latestResult -> lastSuccessful
        }
      }

      val t = rootTracer
      val cancel = cancelCompilation
      val logger = ObservedLogger(rawLogger, observer)
      val dir = state.client.getUniqueClassesDirFor(inputs.project)
      val underlying = createReporter(ReporterInputs(inputs.project, cwd, rawLogger))
      val reporter = new ObservedReporter(logger, underlying)
      CompileBundle.computeFrom(inputs, dir, reporter, last, prev, cancel, logger, obs, t)
    }

    val client = state.client
    CompileGraph.traverse(dag, client, setup(_), compile(_), pipeline).flatMap { partialDag =>
      val partialResults = Dag.dfs(partialDag)
      val finalResults = partialResults.map(r => PartialCompileResult.toFinalResult(r))
      Task.gatherUnordered(finalResults).map(_.flatten).flatMap { results =>
        val cleanUpTasksToRunInBackground =
          markUnusedClassesDirAndCollectCleanUpTasks(results, rawLogger)

        val failures = results.flatMap {
          case FinalNormalCompileResult(p, results) =>
            results.fromCompiler match {
              case Compiler.Result.NotOk(_) => List(p)
              // Consider success with reported fatal warnings as error to simulate -Xfatal-warnings
              case s: Compiler.Result.Success if s.reportedFatalWarnings => List(p)
              case _ => Nil
            }
          case _ => Nil
        }

        val newState: State = {
          val stateWithResults = state.copy(results = state.results.addFinalResults(results))
          if (failures.isEmpty) {
            stateWithResults.copy(status = ExitStatus.Ok)
          } else {
            results.foreach {
              case FinalNormalCompileResult.HasException(project, t) =>
                rawLogger
                  .error(s"Unexpected error when compiling ${project.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                t.printStackTrace()
                rawLogger.trace(t)
              case _ => () // Do nothing when the final compilation result is not an actual error
            }

            // Reverse list of failed projects to get ~correct order of failure
            val projectsFailedToCompile = failures.map(p => s"'${p.name}'").reverse
            val failureMessage =
              if (failures.size <= 2) projectsFailedToCompile.mkString(",")
              else {
                s"${projectsFailedToCompile.take(2).mkString(", ")} and ${projectsFailedToCompile.size - 2} more projects"
              }

            rawLogger.error("Failed to compile " + failureMessage)
            stateWithResults.copy(status = ExitStatus.CompilationError)
          }
        }

        // Schedule to run clean up tasks in the background
        runIOTasksInParallel(cleanUpTasksToRunInBackground)

        val runningTasksRequiredForCorrectness = Task.sequence {
          results.flatMap {
            case FinalNormalCompileResult(_, result) =>
              val tasksAtEndOfBuildCompilation =
                Task.fromFuture(result.runningBackgroundTasks)
              List(tasksAtEndOfBuildCompilation)
            case _ => Nil
          }
        }

        // Block on all background task that are running and are required for correctness
        runningTasksRequiredForCorrectness
          .executeOn(ExecutionContext.ioScheduler)
          .map(_ => newState)
          .doOnFinish(_ => Task(rootTracer.terminate()))
      }
    }
  }

  private final val GeneratePicklesFlag = "-Ygenerate-pickles"
  case class ConfiguredCompilation(mode: CompileMode, scalacOptions: List[String])
  private def configureCompilation(
      project: Project,
      graphInputs: CompileGraph.Inputs,
      out: CompileOutPaths
  ): ConfiguredCompilation = {
    graphInputs.pipelineInputs match {
      case Some(inputs) =>
        val scalacOptions = project.scalacOptions
        val newMode = CompileMode.Pipelined(
          inputs.completeJava,
          inputs.finishedCompilation,
          inputs.transitiveJavaSignal,
          graphInputs.oracle,
          inputs.separateJavaAndScala
        )
        ConfiguredCompilation(newMode, scalacOptions)
      case None =>
        val newMode = CompileMode.Sequential(graphInputs.oracle)
        ConfiguredCompilation(newMode, project.scalacOptions)
    }
  }

  private def runPipeliningBookkeeping(
      inputs: CompileGraph.Inputs,
      result: Compiler.Result,
      javaOnly: Boolean,
      logger: Logger
  ): Unit = {
    val projectName = inputs.bundle.project.name
    // Avoid deadlocks in case pipelining is disabled in the Zinc bridge
    inputs.pipelineInputs match {
      case None => ()
      case Some(pipelineInputs) =>
        result match {
          case Compiler.Result.NotOk(_) =>
            // If error, try to set failure in IR promise; if already completed ignore
            pipelineInputs.irPromise.tryFailure(CompileExceptions.FailedOrCancelledPromise); ()
          case result =>
            // Complete finished compilation promise with products if success or empty
            result match {
              case s: Compiler.Result.Success =>
                pipelineInputs.finishedCompilation.success(Some(s.products))
              case Compiler.Result.Empty =>
                pipelineInputs.finishedCompilation.trySuccess(None)
              case _ =>
                pipelineInputs.finishedCompilation.tryFailure(CompileExceptions.CompletePromise)
            }

            val completed = pipelineInputs.irPromise.tryFailure(CompileExceptions.CompletePromise)
            if (completed && !javaOnly) {
              logger.warn(s"The project $projectName didn't use pipelined compilation.")
            }
        }
    }
  }

  private def populateNewReadOnlyClassesDir(
      products: CompileProducts,
      tracer: BraveTracer,
      logger: Logger
  ): Task[Unit] = {
    // Do nothing if origin and target classes dir are the same, as protective measure
    if (products.readOnlyClassesDir == products.newClassesDir) {
      logger.warn(s"Running `populateNewReadOnlyClassesDir` on same dir ${products.newClassesDir}")
      Task.unit
    } else {
      // Blacklist ensure final dir doesn't contain class files that don't map to source files
      val blacklist = products.invalidatedCompileProducts.iterator.map(_.toPath).toSet
      val config = ParallelOps.CopyConfiguration(5, CopyMode.NoReplace, blacklist)
      val task = tracer.traceTask("preparing new read-only classes directory") { _ =>
        ParallelOps.copyDirectories(config)(
          products.readOnlyClassesDir,
          products.newClassesDir,
          ExecutionContext.ioScheduler,
          logger,
          enableCancellation = false
        )
      }

      task.map(rs => ()).memoize
    }
  }

  private def markUnusedClassesDirAndCollectCleanUpTasks(
      results: List[FinalCompileResult],
      logger: Logger
  ): List[Task[Unit]] = {
    val cleanUpTasksToSpawnInBackground = mutable.ListBuffer[Task[Unit]]()
    results.foreach { finalResult =>
      val resultBundle = finalResult.result
      val newSuccessful = resultBundle.successful
      val compilerResult = resultBundle.fromCompiler
      val populateNewProductsTask = newSuccessful.map(_.populatingProducts).getOrElse(Task.unit)
      val cleanUpPreviousLastSuccessful = resultBundle.previous match {
        case None => populateNewProductsTask
        case Some(previousSuccessful) =>
          for {
            previousPopulate <- previousSuccessful.populatingProducts
            populate <- populateNewProductsTask
            _ <- cleanUpPreviousResult(previousSuccessful, compilerResult, logger)
          } yield ()
      }

      cleanUpTasksToSpawnInBackground.+=(cleanUpPreviousLastSuccessful)
    }

    cleanUpTasksToSpawnInBackground.toList
  }

  def runIOTasksInParallel[T](
      tasks: Traversable[Task[T]],
      parallelUnits: Int = Runtime.getRuntime().availableProcessors()
  ): Unit = {
    val aggregatedTask = Task.sequence(
      tasks.toList.grouped(parallelUnits).map(group => Task.gatherUnordered(group))
    )
    aggregatedTask.map(_ => ()).runAsync(ExecutionContext.ioScheduler)
    ()
  }

  /**
   * Prepares a clean up task that will delete the previous used read-only
   * classes directory used as the read-only directory of an effectful compile.
   *
   * If a compilation is a no-op or its used counter is bigger than zero, the
   * deletion is skipped because in both scenarios the classes directory is
   * still useful for either a next compile or is being used by an alternative
   * running compilation process. The last process to own the classes directory
   * and not use it will be in charge of cleaning up the used read-only classes
   * directory as it's superseeded by the new classes directory generated
   * during a successful compile.
   */
  private def cleanUpPreviousResult(
      previousSuccessful: LastSuccessfulResult,
      compilerResult: Compiler.Result,
      logger: Logger
  ): Task[Unit] = {
    val previousClassesDir = previousSuccessful.classesDir
    val currentlyUsedCounter = previousSuccessful.counterForClassesDir.decrementAndGet(1)

    val previousReadOnlyToDelete = compilerResult match {
      case Success(_, _, products, _, _, isNoOp, _) =>
        if (isNoOp) {
          logger.debug(s"Skipping delete of ${previousClassesDir} associated with no-op result")
          None
        } else if (CompileOutPaths.hasEmptyClassesDir(previousClassesDir)) {
          logger.debug(s"Skipping delete of empty classes dir ${previousClassesDir}")
          None
        } else if (currentlyUsedCounter != 0) {
          logger.debug(s"Skipping delete of $previousClassesDir, counter is $currentlyUsedCounter")
          None
        } else {
          val newClassesDir = products.newClassesDir
          logger.debug(s"Scheduling to delete ${previousClassesDir} superseeded by $newClassesDir")
          Some(previousClassesDir)
        }
      case _ => None
    }

    previousReadOnlyToDelete match {
      case None => Task.unit
      case Some(classesDir) =>
        Task.fork(Task.eval {
          logger.debug(s"Deleting contents of orphan dir $classesDir")
          BloopPaths.delete(classesDir)
        })
    }
  }
}
