package bloop.engine.tasks

import bloop.cli.ExitStatus
import bloop.data.Project
import bloop.engine._
import bloop.engine.tasks.compilation.{FinalCompileResult, _}
import bloop.io.{AbsolutePath, Timer}
import bloop.logging.{BspServerLogger, DebugFilter, Logger, ObservedLogger, LoggerAction}
import bloop.util.JavaCompat.EnrichOptional
import bloop.engine.caches.ResultsCache
import bloop.{CompileInputs, CompileMode, Compiler}
import bloop.reporter.{
  Reporter,
  ReporterAction,
  ReporterConfig,
  ReporterInputs,
  LogReporter,
  ObservedReporter
}

import monix.eval.Task
import monix.reactive.{Observer, Observable, MulticastStrategy}
import sbt.internal.inc.AnalyzingCompiler
import sbt.internal.inc.{ConcreteAnalysisContents, FileAnalysisStore}
import xsbti.compile.{CompileAnalysis, MiniSetup}
import xsbti.compile.PreviousResult

import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.collection.JavaConverters._

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
      rawLogger: UseSiteLogger,
      out: CompileOutPaths
  ): Task[State] = {
    val cwd = state.build.origin.getParent
    val persistenceTasks = new ConcurrentHashMap[Project, PersistAnalysisTask]()

    def compile(graphInputs: CompileGraph.Inputs): Task[Compiler.Result] = {
      val bundle = graphInputs.bundle
      val project = bundle.project
      val logger = bundle.logger
      val reporter = bundle.reporter

      bundle.toBundleInputs(out) match {
        case Left(earlyResult) =>
          val complete = CompileExceptions.CompletePromise(graphInputs.store)
          graphInputs.irPromise.completeExceptionally(complete)
          graphInputs.completeJava.complete(())
          Task.now(earlyResult)
        case Right(CompileBundleInputs(sources, instance, classesDir, classpath, javaOnly)) =>
          val previousResult = state.results.latestResult(project)
          val previousSuccesful = state.results.lastSuccessfulResultOrEmpty(project)

          // Warn user if detected missing dep, see https://github.com/scalacenter/bloop/issues/708
          state.build.hasMissingDependencies(project).foreach { missing =>
            Feedback.detectMissingDependencies(project, missing).foreach(msg => logger.warn(msg))
          }

          val (scalacOptions, compileMode) =
            computeOptionsAndMode(project, pipeline, userCompileMode, graphInputs)
          val inputs = CompilerPluginWhitelist
            .enablePluginCaching(instance.version, scalacOptions, logger)
            .map { scalacOptions =>
              CompileInputs(
                instance,
                state.compilerCache,
                sources.toArray,
                classpath,
                graphInputs.store,
                classesDir,
                project.out,
                scalacOptions.toArray,
                project.javacOptions.toArray,
                project.compileOrder,
                project.classpathOptions,
                previousSuccesful,
                previousResult,
                reporter,
                logger,
                compileMode,
                graphInputs.dependentResults,
                cancelCompilation
              )
            }

          inputs.flatMap(inputs => Compiler.compile(inputs)).map { result =>
            // Apply these side effects before returning the compilation result
            handleIRPromise(project, result, graphInputs, pipeline, javaOnly, logger)
            scheduleAnalysisPersistence(project, result, state, persistenceTasks)
            result
          }
      }
    }

    def setup(project: Project, dag: Dag[Project]): Task[CompileBundle] = {
      // Create a multicast observable stream to allow multiple mirrors of loggers
      val (observer, observable) = {
        Observable.multicast[Either[ReporterAction, LoggerAction]](
          MulticastStrategy.replay
        )(ExecutionContext.ioScheduler)
      }

      val logger = ObservedLogger(rawLogger, observer)
      val underlying = createReporter(ReporterInputs(project, cwd, rawLogger))
      val reporter = new ObservedReporter(logger, underlying)
      CompileBundle.computeFrom(project, dag, reporter, logger, observable)
    }

    CompileGraph.traverse(dag, setup(_, _), compile(_), pipeline).flatMap { partialDag =>
      val partialResults = Dag.dfs(partialDag)
      val finalResults = partialResults.map(r => PartialCompileResult.toFinalResult(r))
      Task.gatherUnordered(finalResults).map(_.flatten).flatMap { results =>
        triggerPostCompileSideEffects(dag, results, state).map { _ =>
          val bgTasks = new scala.collection.mutable.ListBuffer[BackgroundTask[Unit]]()
          val persistentResults = persistenceTasks.asScala.foldLeft(state.results) {
            case (results, (project, task)) =>
              bgTasks.+=(BackgroundTask(task.writeFuture))
              results.addPersistedAnalysis(project, task.analysisId)
          }

          val newResults = persistentResults.addFinalResults(results)
          val newState = state.copy(results = newResults).copy(backgroundTasks = bgTasks.toList)
          val failures = results.collect {
            case FinalNormalCompileResult(p, Compiler.Result.NotOk(_), _) => p
          }

          if (failures.isEmpty) newState.copy(status = ExitStatus.Ok)
          else {
            results.foreach {
              case FinalNormalCompileResult(bundle, Compiler.Result.Failed(_, Some(t), _), _) =>
                val project = bundle.project
                rawLogger
                  .error(s"Unexpected error when compiling ${project.name}: '${t.getMessage}'")
                // Make a better job here at reporting any throwable that happens during compilation
                t.printStackTrace()
                rawLogger.trace(t)
              case _ => () // Do nothing when the final compilation result is not an actual error
            }

            failures.foreach(b => rawLogger.error(s"'${b.project.name}' failed to compile."))
            newState.copy(status = ExitStatus.CompilationError)
          }
        }
      }
    }
  }

  import xsbti.compile.{IRStore, EmptyIRStore}
  private def triggerPostCompileSideEffects(
      dag: Dag[Project],
      results: List[FinalCompileResult],
      state: State
  ): Task[Unit] = {
    def cleanAllScalaInstances: Task[Unit] = {
      val randomProjectFromDag = Dag.dfs(dag).head
      // Merge IRs of all stores (no matter if they were generated by different Scala instances)
      val mergedStore = results.foldLeft(EmptyIRStore.getStore: IRStore) {
        case (acc, result) => acc.merge(result.store)
      }

      // TODO: Turn to `Task.gatherUnordered` to do this in parallel
      val allCleanActions = Task.sequence {
        results.map {
          case FinalNormalCompileResult(bundle, _, _) =>
            bundle.project.scalaInstance match {
              case Some(instance) =>
                Task(cleanStatePerBuildRun(randomProjectFromDag, instance, mergedStore, state))
              case None => Task.now(())
            }
          case FinalEmptyResult => Task.now(())
        }
      }

      allCleanActions.map(_ => ())
    }

    cleanAllScalaInstances
  }

  // Running this method takes around 10ms per compiler and it's rare to have more than one to reset!
  private def cleanStatePerBuildRun(
      randomProjectFromDag: Project,
      instance: bloop.ScalaInstance,
      mergedStore: IRStore,
      state: State
  ): Unit = {
    import xsbti.compile.{IRStore, EmptyIRStore}
    import java.nio.file.Files
    val tmpDir = Files.createTempDirectory("outputDir")
    try {
      // Initialize a compiler so that we can reset the global state after a build compilation
      val scalac = state.compilerCache.get(instance).scalac().asInstanceOf[AnalyzingCompiler]
      val config = ReporterConfig.defaultFormat
      val cwd = state.commonOptions.workingPath
      val logger = ObservedLogger.dummy(state.logger, ExecutionContext.ioScheduler)
      val reporter = new LogReporter(randomProjectFromDag, logger, cwd, config)
      val output = new sbt.internal.inc.ConcreteSingleOutput(tmpDir.toFile)
      val cached = scalac.newCachedCompiler(Array.empty[String], output, logger, reporter)
      // Reset the global ir caches on the cached compiler only for the store IRs
      scalac.resetGlobalIRCaches(mergedStore, cached, logger)
    } finally {
      Files.delete(tmpDir)
    }
  }

  case class PersistAnalysisTask(analysisId: Long, writeFuture: Future[Unit])

  def persistAnalysis(
      project: Project,
      result: PreviousResult,
      cache: ResultsCache,
      logger: Logger
  ): Option[PersistAnalysisTask] = {
    def writeBinaryFile(id: Long, analysis: CompileAnalysis, setup: MiniSetup): Task[Unit] = Task {
      val analysisId = s"analysis-${id}.bin.temp"
      val temporaryStoreFile = project.analysisOut.getParent.resolve(analysisId)
      val storeFile = project.analysisOut

      Timer.timed(logger.info(_), Some(s"writing to ${storeFile.syntax}")) {
        // Write to temporary file in the parent of the target out
        FileAnalysisStore
          .binary(temporaryStoreFile.toFile)
          .set(ConcreteAnalysisContents(analysis, setup))
        // Move the temporary file to the target path atomically
        Files.move(
          temporaryStoreFile.underlying,
          project.analysisOut.underlying,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE
        )
      }

      ()
    }

    val setup = result.setup().toOption
    val analysis = result.analysis().toOption
    (analysis, setup) match {
      case (Some(analysis), Some(setup)) =>
        val startCompilationTime = ResultsCache.getStartCompilationTime(analysis)
        val previousCompilationTime = cache.getCompilationTimeOfLastPersistedAnalysis(project)
        if (previousCompilationTime == startCompilationTime) None
        else {
          val task = writeBinaryFile(startCompilationTime, analysis, setup)
          val future = task.runAsync(ExecutionContext.ioScheduler)
          Some(PersistAnalysisTask(startCompilationTime, future))
        }

      case unexpectedAnalysisAndSetup =>
        unexpectedAnalysisAndSetup match {
          case (Some(_), Some(_)) => ()
          case (Some(analysis), None) =>
            logger.warn(
              s"$project has analysis but not setup after compilation. Report upstream."
            )
          case (None, Some(analysis)) =>
            logger.warn(
              s"$project has setup but not analysis after compilation. Report upstream."
            )
          case (None, None) =>
            logger.debug(
              s"Project $project has no analysis and setup."
            )(DebugFilter.Compilation)
        }

        // Return the previous results cache as the analysis was not saved to the file system
        None
    }
  }

  def computeOptionsAndMode(
      project: Project,
      pipeline: Boolean,
      userCompileMode: CompileMode.ConfigurableMode,
      graphInputs: CompileGraph.Inputs
  ): (List[String], CompileMode) = {
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

  private def handleIRPromise(
      project: Project,
      result: Compiler.Result,
      inputs: CompileGraph.Inputs,
      pipeline: Boolean,
      javaOnly: Boolean,
      logger: Logger
  ): Unit = {
    // Do some implementation book-keeping before returning the compilation result
    if (!inputs.irPromise.isDone) {
      /*
       * When pipeline compilation is disabled either by the user or the implementation
       * decides not to use it (in the presence of macros from the same build), we force
       * the completion of the pickle promise to avoid deadlocks.
       */
      result match {
        case Compiler.Result.NotOk(_) =>
          inputs.irPromise.completeExceptionally(CompileExceptions.FailPromise); ()
        case result =>
          if (pipeline && !javaOnly)
            logger.warn(s"The project ${project.name} didn't use pipelined compilation.")
          val complete = CompileExceptions.CompletePromise(inputs.store)
          inputs.irPromise.completeExceptionally(complete); ()
      }
    } else {
      // Report if the pickle ready was correctly completed by the compiler
      inputs.irPromise.get match {
        case Array() if pipeline =>
          logger.warn(s"Project ${project.name} compiled without pipelined compilation.")
        case _ => logger.debug(s"The pickle promise of ${project.name} completed in Zinc.")
      }
    }
  }

  private def scheduleAnalysisPersistence(
      project: Project,
      result: Compiler.Result,
      state: State,
      persistenceTasks: ConcurrentHashMap[Project, PersistAnalysisTask]
  ): Unit = {
    result match {
      case s: Compiler.Result.Success =>
        persistAnalysis(project, s.previous, state.results, state.logger) match {
          case Some(task) => persistenceTasks.putIfAbsent(project, task); ()
          case None => ()
        }
      case _ => ()
    }
  }
}
