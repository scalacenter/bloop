package bloop

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.concurrent.Executor

import scala.collection.mutable
import scala.concurrent.Promise
import scala.util.Try

import bloop.Compiler.Result.Failed
import bloop.Compiler.Result.Ok
import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode
import bloop.io.{Paths => BloopPaths}
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.logging.ObservedLogger
import bloop.logging.CompilationTraceRecorder
import bloop.logging.CompilationArtifact
import bloop.reporter.ProblemPerPhase
import bloop.reporter.Reporter
import bloop.reporter.ZincReporter
import bloop.rtexport.RtJarCache
import bloop.task.Task
import bloop.tracing.BraveTracer
import bloop.util.AnalysisUtils
import bloop.util.BestEffortUtils
import bloop.util.BestEffortUtils.BestEffortProducts
import bloop.util.CacheHashCode
import bloop.util.JavaRuntime
import bloop.util.UUIDUtil

import monix.execution.Scheduler
import sbt.internal.inc.Analysis
import sbt.internal.inc.CompileFailed
import sbt.internal.inc.ConcreteAnalysisContents
import sbt.internal.inc.FileAnalysisStore
import sbt.internal.inc.FreshCompilerCache
import sbt.internal.inc.PlainVirtualFileConverter
import sbt.internal.inc.bloop.BloopZincCompiler
import sbt.internal.inc.bloop.internal.BloopLookup
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.util.InterfaceUtil
import xsbti.T2
import xsbti.VirtualFileRef
import xsbti.compile._

case class CompileInputs(
    scalaInstance: ScalaInstance,
    compilerCache: CompilerCache,
    sources: Array[AbsolutePath],
    classpath: Array[AbsolutePath],
    uniqueInputs: UniqueCompileInputs,
    out: CompileOutPaths,
    baseDirectory: AbsolutePath,
    scalacOptions: Array[String],
    javacOptions: Array[String],
    javacBin: Option[AbsolutePath],
    compileOrder: CompileOrder,
    classpathOptions: ClasspathOptions,
    previousResult: PreviousResult,
    previousCompilerResult: Compiler.Result,
    reporter: ZincReporter,
    logger: ObservedLogger[Logger],
    dependentResults: Map[File, PreviousResult],
    cancelPromise: Promise[Unit],
    tracer: BraveTracer,
    ioScheduler: Scheduler,
    ioExecutor: Executor,
    invalidatedClassFilesInDependentProjects: Set[File],
    generatedClassFilePathsInDependentProjects: Map[String, File],
    resources: List[AbsolutePath],
    compilationTraceRecorder: Option[CompilationTraceRecorder]
)

case class CompileOutPaths(
    out: AbsolutePath,
    analysisOut: AbsolutePath,
    genericClassesDir: AbsolutePath,
    externalClassesDir: AbsolutePath,
    internalReadOnlyClassesDir: AbsolutePath
) {
  // Don't change the internals of this method without updating how they are cleaned up
  private def createInternalNewDir(generateDirName: String => String): AbsolutePath = {
    val parentInternalDir = CompileOutPaths.createInternalClassesRootDir(out).underlying

    /*
     * Use external classes dir as the beginning of the new internal directory name.
     * This is done for two main reasons:
     *   1. To easily know which internal directory was triggered by a
     *      compilation of another client.
     *   2. To ease cleanup of orphan directories (those directories that were
     *      not removed by the server because, for example, an unexpected SIGKILL)
     *      in the middle of compilation.
     */
    val classesName = externalClassesDir.underlying.getFileName()
    val newClassesName = generateDirName(classesName.toString)
    AbsolutePath(Files.createDirectories(parentInternalDir.resolve(newClassesName)).toRealPath())
  }

  lazy val internalNewClassesDir: AbsolutePath = {
    createInternalNewDir(classesName => s"${classesName}-${UUIDUtil.randomUUID}")
  }

  /**
   * Creates an internal directory where symbol pickles are stored when build
   * pipelining is enabled. This directory is removed whenever compilation
   * process has finished because pickles are useless when class files are
   * present. This might change when we expose pipelining to clients.
   *
   * Watch out: for the moment this pickles dir is not used anywhere.
   */
  lazy val internalNewPicklesDir: AbsolutePath = {
    createInternalNewDir { classesName =>
      val newName = s"${classesName.replace("classes", "pickles")}-${UUIDUtil.randomUUID}"
      // If original classes name didn't contain `classes`, add pickles at the beginning
      if (newName.contains("pickles")) newName
      else "pickles-" + newName
    }
  }
}

object CompileOutPaths {

  /**
   * Defines a project-specific root directory where all the internal classes
   * directories used for compilation are created.
   */
  def createInternalClassesRootDir(out: AbsolutePath): AbsolutePath = {
    AbsolutePath(
      Files.createDirectories(
        out.resolve("bloop-internal-classes").underlying
      )
    )
  }

  /*
   * An empty classes directory never exists on purpose. It is merely a
   * placeholder until a non empty classes directory is used. There is only
   * one single empty classes directory per project and can be shared by
   * different projects, so to avoid problems across different compilations
   * we never create this directory and special case Zinc logic to skip it.
   *
   * The prefix name 'classes-empty-` of this classes directory should not
   * change without modifying `BloopLookup` defined in `backend`.
   */
  def deriveEmptyClassesDir(projectName: String, genericClassesDir: AbsolutePath): AbsolutePath = {
    val classesDirName = s"classes-empty-${projectName}"
    genericClassesDir.getParent.resolve(classesDirName)
  }

  private val ClassesEmptyDirPrefix = java.io.File.separator + "classes-empty-"
  def hasEmptyClassesDir(classesDir: AbsolutePath): Boolean = {
    /*
     * Empty classes dirs don't exist so match on path.
     *
     * Don't match on `getFileName` because `classes-empty` is followed by
     * target name, which could contains `java.io.File.separator`, making
     * `getFileName` pick the suffix after the latest separator.
     *
     * e.g. if target name is
     * `util/util-function/src/main/java/com/twitter/function:function`
     * classes empty dir path will be
     * `classes-empty-util/util-function/src/main/java/com/twitter/function:function`.
     * and `getFileName` would yield `function:function` which is not what we want.
     * Hence we avoid using this code for the implementation:
     * `classesDir.underlying.getFileName().toString.startsWith("classes-empty-")`
     */
    classesDir.syntax.contains(ClassesEmptyDirPrefix)
  }
}

object Compiler {
  private implicit val filter: DebugFilter.Compilation.type = bloop.logging.DebugFilter.Compilation
  private val converter = PlainVirtualFileConverter.converter
  private final class BloopProgress(
      reporter: ZincReporter,
      cancelPromise: Promise[Unit]
  ) extends CompileProgress {
    override def startUnit(phase: String, unitPath: String): Unit = {
      reporter.reportNextPhase(phase, new java.io.File(unitPath))
    }

    override def advance(
        current: Int,
        total: Int,
        prevPhase: String,
        nextPhase: String
    ): Boolean = {
      val isNotCancelled = !cancelPromise.isCompleted
      if (isNotCancelled) {
        reporter.reportCompilationProgress(current.toLong, total.toLong)
      }

      isNotCancelled
    }
  }

  sealed trait Result
  object Result {
    final case object Empty extends Result with CacheHashCode
    final case class Blocked(on: List[String]) extends Result with CacheHashCode
    final case class GlobalError(problem: String, err: Option[Throwable])
        extends Result
        with CacheHashCode

    final case class Success(
        inputs: UniqueCompileInputs,
        reporter: ZincReporter,
        products: CompileProducts,
        elapsed: Long,
        backgroundTasks: CompileBackgroundTasks,
        isNoOp: Boolean,
        reportedFatalWarnings: Boolean
    ) extends Result
        with CacheHashCode

    final case class Failed(
        problems: List[ProblemPerPhase],
        t: Option[Throwable],
        elapsed: Long,
        backgroundTasks: CompileBackgroundTasks,
        bestEffortProducts: Option[BestEffortProducts]
    ) extends Result
        with CacheHashCode

    final case class Cancelled(
        problems: List[ProblemPerPhase],
        elapsed: Long,
        backgroundTasks: CompileBackgroundTasks
    ) extends Result
        with CacheHashCode

    object Ok {
      def unapply(result: Result): Option[Result] = result match {
        case s @ (Success(_, _, _, _, _, _, _) | Empty) => Some(s)
        case _ => None
      }
    }

    object NotOk {
      def unapply(result: Result): Option[Result] = result match {
        case f @ (_: Failed | _: Cancelled | _: Blocked | _: GlobalError) =>
          Some(f)
        case _ => None
      }
    }
  }

  private def isFatalWarningOpt(opt: String) = opt == "-Xfatal-warnings" || opt == "-Werror"

  def compile(
      compileInputs: CompileInputs,
      isBestEffortMode: Boolean,
      isBestEffortDep: Boolean,
      firstCompilation: Boolean
  ): Task[Result] = {
    val logger = compileInputs.logger
    val tracer = compileInputs.tracer
    val compileOut = compileInputs.out
    val cancelPromise = compileInputs.cancelPromise
    val externalClassesDir = compileOut.externalClassesDir.underlying
    val externalClassesDirPath = externalClassesDir.toString
    val readOnlyClassesDir = compileOut.internalReadOnlyClassesDir.underlying
    val readOnlyClassesDirPath = readOnlyClassesDir.toString
    val newClassesDir = compileOut.internalNewClassesDir.underlying
    val newClassesDirPath = newClassesDir.toString

    // Start compilation trace if recorder is available
    val projectName = compileInputs.uniqueInputs.sources.headOption
      .map(_.getParent.getParent.getFileName.toString)
      .getOrElse("unknown")
    compileInputs.compilationTraceRecorder.foreach(_.startCompilation(projectName))

    def recordCompilationEnd(
        result: String,
        isNoOp: Boolean = false,
        products: Option[CompileProducts] = None
    ): Unit = {
      compileInputs.compilationTraceRecorder.foreach { recorder =>
        val compiledFiles = compileInputs.sources.map(_.syntax).toList
        val reporter = compileInputs.reporter
        val diagnostics = reporter.allProblemsPerPhase.flatMap(_.problem).toList
        val artifacts = products.map { p =>
          List(
            CompilationArtifact(
              source = p.newClassesDir.syntax,
              destination = compileOut.externalClassesDir.syntax,
              artifactType = "class"
            ),
            CompilationArtifact(
              source = p.analysisOut.syntax,
              destination = compileOut.analysisOut.syntax,
              artifactType = "analysis"
            )
          )
        }.getOrElse(List.empty)
        
        recorder.endCompilation(
          projectName,
          compiledFiles,
          diagnostics,
          artifacts,
          isNoOp,
          result
        )
      }
    }

    logger.debug(s"External classes directory ${externalClassesDirPath}")
    logger.debug(s"Read-only classes directory ${readOnlyClassesDirPath}")
    logger.debug(s"New rw classes directory ${newClassesDirPath}")

    val allGeneratedRelativeClassFilePaths = new mutable.HashMap[String, File]()
    val readOnlyCopyDenylist = new mutable.HashSet[Path]()
    val allInvalidatedClassFilesForProject = new mutable.HashSet[File]()
    val allInvalidatedExtraCompileProducts = new mutable.HashSet[File]()

    val backgroundTasksWhenNewSuccessfulAnalysis =
      new mutable.ListBuffer[CompileBackgroundTasks.Sig]()
    val backgroundTasksForFailedCompilation =
      new mutable.ListBuffer[CompileBackgroundTasks.Sig]()

    def newFileManager: BloopClassFileManager = {
      new BloopClassFileManager(
        Files.createTempDirectory("bloop"),
        compileInputs,
        compileOut,
        allGeneratedRelativeClassFilePaths,
        readOnlyCopyDenylist,
        allInvalidatedClassFilesForProject,
        allInvalidatedExtraCompileProducts,
        backgroundTasksWhenNewSuccessfulAnalysis,
        backgroundTasksForFailedCompilation
      )
    }

    val isFatalWarningsEnabled: Boolean =
      compileInputs.scalacOptions.exists(isFatalWarningOpt)
    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs, logger, newClassesDir)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getSetup(compileInputs: CompileInputs): Setup = {
      val skip = false
      val empty = Array.empty[T2[String, String]]
      val results = compileInputs.dependentResults.+(
        readOnlyClassesDir.toFile -> compileInputs.previousResult,
        newClassesDir.toFile -> compileInputs.previousResult
      )

      val lookup = new BloopClasspathEntryLookup(
        results,
        compileInputs.uniqueInputs.classpath,
        converter
      )
      val reporter = compileInputs.reporter
      val compilerCache = new FreshCompilerCache
      val cacheFile = compileInputs.baseDirectory.resolve("cache").toFile
      val incOptions = {
        val disableIncremental = java.lang.Boolean.getBoolean("bloop.zinc.disabled")
        // Don't customize class file manager bc we pass our own to the zinc APIs directly
        IncOptions.create().withEnabled(!disableIncremental)
      }

      val progress = Optional.of[CompileProgress](new BloopProgress(reporter, cancelPromise))
      Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
    }

    val start = System.nanoTime()
    val scalaInstance = compileInputs.scalaInstance
    val classpathOptions = compileInputs.classpathOptions
    val compilers = compileInputs.compilerCache.get(
      scalaInstance,
      classpathOptions,
      compileInputs.javacBin,
      compileInputs.javacOptions.toList
    )
    val inputs = tracer.traceVerbose("creating zinc inputs")(_ => getInputs(compilers))

    // We don't need nanosecond granularity, we're happy with milliseconds
    def elapsed: Long = ((System.nanoTime() - start).toDouble / 1e6).toLong

    import ch.epfl.scala.bsp
    import scala.util.{Success, Failure}
    val reporter = compileInputs.reporter

    def cancel(): Unit = {
      // Complete all pending promises when compilation is cancelled
      logger.debug(s"Cancelling compilation from ${readOnlyClassesDirPath} to ${newClassesDirPath}")
      compileInputs.cancelPromise.trySuccess(())

      // Always report the compilation of a project no matter if it's completed
      reporter.reportCancelledCompilation()
    }

    val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
    val previousSuccessfulProblems = previousProblemsFromSuccessfulCompilation(previousAnalysis)
    val previousProblems =
      previousProblemsFromResult(compileInputs.previousCompilerResult, previousSuccessfulProblems)

    def handleCancellation: Compiler.Result = {
      val cancelledCode = bsp.StatusCode.Cancelled
      reporter.processEndCompilation(previousSuccessfulProblems, cancelledCode, None, None)
      reporter.reportEndCompilation()
      val backgroundTasks =
        toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
      recordCompilationEnd("Cancelled")
      Result.Cancelled(reporter.allProblemsPerPhase.toList, elapsed, backgroundTasks)
    }

    val uniqueInputs = compileInputs.uniqueInputs
    val wasPreviousSuccessful =
      compileInputs.previousCompilerResult match {
        case Ok(_) => true
        case _ => false
      }

    reporter.reportStartCompilation(previousProblems, wasPreviousSuccessful)
    val fileManager = newFileManager

    val shouldAttemptRestartingCompilationForBestEffort =
      firstCompilation && !isBestEffortDep && previousAnalysis.isDefined

    // Manually skip redundant best-effort compilations. This is necessary because compiler
    // phases supplying the data needed to skip compilations in zinc remain unimplemented for now.
    val (noopBestEffortResult, inputHash) = compileInputs.previousCompilerResult match {
      case Failed(
            problems,
            t,
            elapsed,
            _,
            bestEffortProducts @ Some(
              BestEffortProducts(previousCompilationResults, previousHash, _)
            )
          ) if isBestEffortMode =>
        // We filter out certain directories from classpath, as:
        // * we do not want to hash readOnlyClassesDir, as that contains classfiles unrelated to
        // best effort compilation
        // * newClassesDir on the classpath of the previous successful best effort compilation
        // is also different from newClassesDir of the current compilation.
        val newHash = BestEffortUtils.hashAll(
          previousCompilationResults.newClassesDir,
          compileInputs.sources,
          compileInputs.classpath,
          ignoredClasspathDirectories = List(
            compileOut.internalReadOnlyClassesDir,
            compileOut.internalNewClassesDir
          )
        )

        if (newHash == previousHash) {
          reporter.processEndCompilation(
            problems,
            bsp.StatusCode.Error,
            None,
            None
          )
          reporter.reportEndCompilation()
          val backgroundTasks = new CompileBackgroundTasks {
            def trigger(
                clientClassesObserver: ClientClassesObserver,
                clientReporter: Reporter,
                clientTracer: BraveTracer,
                clientLogger: Logger
            ): Task[Unit] = Task.defer {
              val clientClassesDir = clientClassesObserver.classesDir
              clientLogger.debug(s"Triggering background tasks for $clientClassesDir")

              // First, we delete newClassesDir, as it was created to store
              // new compilation artifacts coming from scalac, which we will not
              // have in this case and it's going to remain empty.
              val firstTask = Task { BloopPaths.delete(AbsolutePath(newClassesDir)) }

              // Then we copy previous best effort artifacts to a clientDir from the
              // cached compilation result.
              // This is useful if e.g. the client restarted after the last compilation
              // and was assigned a new, empty directory. Since best-effort currently does
              // not support incremental compilation, all necessary betasty files will come
              // from a single previous compilation run, so that is all we need to copy.
              val config =
                ParallelOps.CopyConfiguration(
                  parallelUnits = 5,
                  CopyMode.ReplaceIfMetadataMismatch,
                  denylist = Set.empty,
                  denyDirs = Set.empty
                )
              val secondTask = ParallelOps.copyDirectories(config)(
                previousCompilationResults.newClassesDir,
                clientClassesDir.underlying,
                compileInputs.ioScheduler,
                enableCancellation = false,
                compileInputs.logger
              )
              Task
                .gatherUnordered(List(firstTask, secondTask))
                .map(_ => ())
            }
          }
          (
            Some(Failed(problems, t, elapsed, backgroundTasks, bestEffortProducts)),
            newHash.inputHash
          )
        } else (None, newHash.inputHash)
      case _ =>
        (
          None,
          if (isBestEffortMode)
            BestEffortUtils.hashInput(
              newClassesDir,
              compileInputs.sources,
              compileInputs.classpath,
              ignoredClasspathDirectories = List(
                compileOut.internalReadOnlyClassesDir,
                compileOut.internalNewClassesDir
              )
            )
          else ""
        )
    }

    if (noopBestEffortResult.isDefined) {
      logger.debug("Skipping redundant best-effort compilation")
      return Task { noopBestEffortResult.get }
    }

    BloopZincCompiler
      .compile(
        inputs,
        reporter,
        logger,
        uniqueInputs,
        fileManager,
        cancelPromise,
        tracer,
        classpathOptions,
        !(isBestEffortMode && isBestEffortDep)
      )
      .materialize
      .doOnCancel(Task(cancel()))
      .map {
        case Success(_) if cancelPromise.isCompleted => handleCancellation
        case Success(_) if isBestEffortMode && isBestEffortDep =>
          handleBestEffortSuccess(
            compileInputs,
            compileOut,
            () => elapsed,
            reporter,
            backgroundTasksWhenNewSuccessfulAnalysis,
            previousSuccessfulProblems,
            errorCause = None,
            shouldAttemptRestartingCompilationForBestEffort,
            inputHash
          )
        case Success(result) =>
          // Report end of compilation only after we have reported all warnings from previous runs
          val sourcesWithFatal = reporter.getSourceFilesWithFatalWarnings
          val reportedFatalWarnings = isFatalWarningsEnabled && sourcesWithFatal.nonEmpty
          val code = if (reportedFatalWarnings) bsp.StatusCode.Error else bsp.StatusCode.Ok

          // Process the end of compilation, but wait for reporting until client tasks run
          reporter.processEndCompilation(
            previousSuccessfulProblems,
            code,
            Some(compileOut.externalClassesDir),
            Some(compileOut.analysisOut)
          )

          // Compute the results we should use for dependent compilations and new compilation runs
          val resultForDependentCompilationsInSameRun =
            PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
          val analysis = result.analysis()

          def persistAnalysis(analysis: CompileAnalysis, out: AbsolutePath): Task[Unit] = {
            // Important to memoize it, it's triggered by different clients
            Task(persist(out, analysis, result.setup, tracer, logger)).memoize
          }

          // .betasty files are always produced with -Ybest-effort, even when
          // the compilation is successful.
          // We might want to change this in the compiler itself...
          if (isBestEffortMode)
            BloopPaths.delete(compileOut.internalNewClassesDir.resolve("META-INF/best-effort"))

          val isNoOp = previousAnalysis.contains(analysis)
          if (isNoOp) {
            // If no-op, return previous result with updated classpath hashes
            val noOpPreviousResult = {
              updatePreviousResultWithRecentClasspathHashes(
                compileInputs.previousResult,
                uniqueInputs
              )
            }

            val products = CompileProducts(
              readOnlyClassesDir,
              readOnlyClassesDir,
              noOpPreviousResult,
              noOpPreviousResult,
              Set(),
              Map.empty
            )

            val backgroundTasks = new CompileBackgroundTasks {
              def trigger(
                  clientClassesObserver: ClientClassesObserver,
                  clientReporter: Reporter,
                  clientTracer: BraveTracer,
                  clientLogger: Logger
              ): Task[Unit] = Task.defer {
                val clientClassesDir = clientClassesObserver.classesDir
                clientLogger.debug(s"Triggering background tasks for $clientClassesDir")
                val updateClientState =
                  Task
                    .gatherUnordered(
                      List(
                        deleteClientExternalBestEffortDirTask(clientClassesDir)
                      )
                    )
                    .flatMap { _ =>
                      updateExternalClassesDirWithReadOnly(
                        clientClassesDir,
                        clientTracer,
                        clientLogger,
                        compileInputs,
                        readOnlyClassesDir,
                        readOnlyCopyDenylist,
                        allInvalidatedClassFilesForProject,
                        allInvalidatedExtraCompileProducts
                      )
                    }

                val writeAnalysisIfMissing = {
                  if (compileOut.analysisOut.exists) Task.unit
                  else {
                    previousAnalysis match {
                      case None => Task.unit
                      case Some(analysis) => persistAnalysis(analysis, compileOut.analysisOut)
                    }
                  }
                }

                val deleteNewClassesDir = Task(BloopPaths.delete(AbsolutePath(newClassesDir)))
                val cleanUpTemporaryFiles = Task(fileManager.deleteTemporaryFiles())
                val publishClientAnalysis = Task {
                  rebaseAnalysisClassFiles(
                    analysis,
                    readOnlyClassesDir,
                    clientClassesDir.underlying,
                    sourcesWithFatal
                  )
                }
                  .flatMap(clientClassesObserver.nextAnalysis)
                Task
                  .gatherUnordered(
                    List(
                      deleteNewClassesDir,
                      updateClientState,
                      writeAnalysisIfMissing,
                      cleanUpTemporaryFiles
                    )
                  )
                  .flatMap(_ => publishClientAnalysis)
                  .onErrorHandleWith(err => {
                    clientLogger.debug("Caught error in background tasks");
                    clientLogger.trace(err);
                    Task.raiseError(err)
                  })
                  .doOnFinish(_ => Task(clientReporter.reportEndCompilation()))
              }
            }
            recordCompilationEnd("Success", isNoOp, Some(products))
            Result.Success(
              compileInputs.uniqueInputs,
              compileInputs.reporter,
              products,
              elapsed,
              backgroundTasks,
              isNoOp,
              reportedFatalWarnings
            )
          } else {
            val allGeneratedProducts = allGeneratedRelativeClassFilePaths.toMap
            val analysisForFutureCompilationRuns = rebaseAnalysisClassFiles(
              analysis,
              readOnlyClassesDir,
              newClassesDir,
              sourcesWithFatal
            )

            val resultForFutureCompilationRuns = {
              resultForDependentCompilationsInSameRun.withAnalysis(
                Optional.of(analysisForFutureCompilationRuns): Optional[CompileAnalysis]
              )
            }

            // Delete all those class files that were invalidated in the external classes dir
            val allInvalidated =
              allInvalidatedClassFilesForProject ++ allInvalidatedExtraCompileProducts

            // Schedule the tasks to run concurrently after the compilation end
            val backgroundTasksExecution = new CompileBackgroundTasks {
              def trigger(
                  clientClassesObserver: ClientClassesObserver,
                  clientReporter: Reporter,
                  clientTracer: BraveTracer,
                  clientLogger: Logger
              ): Task[Unit] = {
                val clientClassesDir = clientClassesObserver.classesDir
                val successBackgroundTasks =
                  Task
                    .gatherUnordered(
                      List(
                        deleteClientExternalBestEffortDirTask(clientClassesDir)
                      )
                    )
                    .flatMap { _ =>
                      Task.gatherUnordered(
                        backgroundTasksWhenNewSuccessfulAnalysis
                          .map(f => f(clientClassesDir, clientReporter, clientTracer))
                      )
                    }
                val persistTask =
                  persistAnalysis(analysisForFutureCompilationRuns, compileOut.analysisOut)
                val initialTasks = List(persistTask, successBackgroundTasks)
                val allClientSyncTasks = Task.gatherUnordered(initialTasks).flatMap { _ =>
                  // Only start these tasks after the previous IO tasks in the external dir are done
                  val firstTask = updateExternalClassesDirWithReadOnly(
                    clientClassesDir,
                    clientTracer,
                    clientLogger,
                    compileInputs,
                    readOnlyClassesDir,
                    readOnlyCopyDenylist,
                    allInvalidatedClassFilesForProject,
                    allInvalidatedExtraCompileProducts
                  )

                  val secondTask = Task {
                    allInvalidated.foreach { f =>
                      val path = AbsolutePath(f.toPath)
                      val syntax = path.syntax
                      if (syntax.startsWith(readOnlyClassesDirPath)) {
                        val rebasedFile = AbsolutePath(
                          syntax.replace(readOnlyClassesDirPath, clientClassesDir.toString)
                        )
                        if (rebasedFile.exists) {
                          Files.delete(rebasedFile.underlying)
                        }
                      }
                    }
                  }

                  val publishClientAnalysis = Task {
                    rebaseAnalysisClassFiles(
                      analysis,
                      newClassesDir,
                      clientClassesDir.underlying,
                      sourcesWithFatal
                    )
                  }.flatMap(clientClassesObserver.nextAnalysis)
                  Task
                    .gatherUnordered(List(firstTask, secondTask))
                    .flatMap(_ => publishClientAnalysis)
                }

                allClientSyncTasks.doOnFinish(_ => Task(clientReporter.reportEndCompilation()))
              }
            }

            val products = CompileProducts(
              readOnlyClassesDir,
              newClassesDir,
              resultForDependentCompilationsInSameRun,
              resultForFutureCompilationRuns,
              allInvalidated.toSet,
              allGeneratedProducts
            )

            recordCompilationEnd("Success", isNoOp, Some(products))
            Result.Success(
              compileInputs.uniqueInputs,
              compileInputs.reporter,
              products,
              elapsed,
              backgroundTasksExecution,
              isNoOp,
              reportedFatalWarnings
            )
          }
        case Failure(cause: xsbti.CompileFailed)
            if isBestEffortMode && !containsBestEffortFailure(cause) =>
          // Copies required files to a bsp directory.
          // For the Success case this is done by the enclosing method
          fileManager.complete(true)
          handleBestEffortSuccess(
            compileInputs,
            compileOut,
            () => elapsed,
            reporter,
            backgroundTasksWhenNewSuccessfulAnalysis,
            previousSuccessfulProblems,
            errorCause = Some(cause),
            shouldAttemptRestartingCompilationForBestEffort,
            inputHash
          )

        case Failure(_: xsbti.CompileCancelled) => handleCancellation
        case Failure(cause) =>
          val errorCode = bsp.StatusCode.Error
          reporter.processEndCompilation(previousSuccessfulProblems, errorCode, None, None)
          reporter.reportEndCompilation()

          cause match {
            case f: xsbti.CompileFailed =>
              val failedProblems = findFailedProblems(reporter, Some(f))
              val backgroundTasks =
                toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
              recordCompilationEnd("Failed")
              Result.Failed(failedProblems, None, elapsed, backgroundTasks, None)
            case t: Throwable =>
              t.printStackTrace()
              val sw = new StringWriter()
              t.printStackTrace(new PrintWriter(sw))
              logger.error(sw.toString())
              val backgroundTasks =
                toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
              val failedProblems = findFailedProblems(reporter, None)
              recordCompilationEnd("Failed")
              Result.Failed(failedProblems, Some(t), elapsed, backgroundTasks, None)
          }
      }
  }

  def updateExternalClassesDirWithReadOnly(
      clientClassesDir: AbsolutePath,
      clientTracer: BraveTracer,
      clientLogger: Logger,
      compileInputs: CompileInputs,
      readOnlyClassesDir: Path,
      readOnlyCopyDenylist: mutable.HashSet[Path],
      allInvalidatedClassFilesForProject: mutable.HashSet[File],
      allInvalidatedExtraCompileProducts: mutable.HashSet[File]
  ): Task[Unit] = Task.defer {
    val descriptionMsg = s"Updating external classes dir with read only $clientClassesDir"
    clientTracer.traceTaskVerbose(descriptionMsg) { _ =>
      Task.defer {
        clientLogger.debug(descriptionMsg)
        val invalidatedClassFiles =
          allInvalidatedClassFilesForProject.iterator.map(_.toPath).toSet
        val invalidatedExtraProducts =
          allInvalidatedExtraCompileProducts.iterator.map(_.toPath).toSet
        val invalidatedInThisProject = invalidatedClassFiles ++ invalidatedExtraProducts
        val denyList = invalidatedInThisProject ++ readOnlyCopyDenylist.iterator
        // Let's not copy outdated betasty from readOnly, since we do not have a mechanism
        // for tracking that otherwise
        val denyDir = Set(readOnlyClassesDir.resolve("META-INF/best-effort"))
        val config =
          ParallelOps.CopyConfiguration(5, CopyMode.ReplaceIfMetadataMismatch, denyList, denyDir)

        val copyResources = ParallelOps.copyResources(
          compileInputs.resources,
          clientClassesDir,
          config,
          compileInputs.logger,
          compileInputs.ioScheduler
        )
        val lastCopy = ParallelOps.copyDirectories(config)(
          readOnlyClassesDir,
          clientClassesDir.underlying,
          compileInputs.ioScheduler,
          enableCancellation = false,
          compileInputs.logger
        )

        Task.gatherUnordered(List(copyResources, lastCopy)).map { _ =>
          clientLogger.debug(
            s"Finished copying classes from $readOnlyClassesDir to $clientClassesDir"
          )
          ()
        }
      }
    }
  }

  def findFailedProblems(
      reporter: ZincReporter,
      compileFailedMaybe: Option[xsbti.CompileFailed]
  ): List[ProblemPerPhase] = {
    // We cannot guarantee reporter.problems == f.problems, so we aggregate them together
    val reportedProblems = reporter.allProblemsPerPhase.toList
    val rawProblemsFromReporter = reportedProblems.iterator.map(_.problem).toSet
    val newProblems: List[ProblemPerPhase] = compileFailedMaybe
      .map { f =>
        f.problems()
          .flatMap { p =>
            if (rawProblemsFromReporter.contains(p)) Nil
            else List(ProblemPerPhase(p, None))
          }
          .toList
      }
      .getOrElse(Nil)
    reportedProblems ++ newProblems.toList
  }

  def containsBestEffortFailure(cause: xsbti.CompileFailed) =
    cause.problems().exists(_.message().contains("Unsuccessful best-effort compilation.")) || cause
      .getCause()
      .isInstanceOf[StackOverflowError]

  /**
   * Bloop runs Scala compilation in the same process as the main server,
   * so the compilation process will use the same JDK that Bloop is using.
   * That's why we must ensure that produce class files will be compliant with expected JDK version
   * and compilation errors will show up when using wrong JDK API.
   */
  private def adjustScalacReleaseOptions(
      scalacOptions: Array[String],
      javacBin: Option[AbsolutePath],
      logger: Logger,
      scalaVersion: String
  ): Array[String] = {
    def existsReleaseSetting = scalacOptions.exists(opt =>
      opt.startsWith("-release") ||
        opt.startsWith("--release") ||
        opt.startsWith("-java-output-version") ||
        opt.startsWith("-Xtarget") ||
        opt.startsWith("-target")
    )
    def sameHome = javacBin match {
      case Some(bin) => bin.getParent.getParent == JavaRuntime.home
      case None => false
    }
    def releaseFlagForVersion(targetJvmVersion: Int): List[String] = {
      // 2.11 does not support release flag
      if (scalaVersion.startsWith("2.11")) Nil
      /* At the moment, Scala does not support release flag for JDK 17
       * This should not be an issue though since users can easily switch to JDK 17 for Bloop
       */
      else if (targetJvmVersion > 17) Nil
      else List("-release", targetJvmVersion.toString())
    }
    javacBin.flatMap(binary =>
      // <JAVA_HOME>/bin/java
      JavaRuntime.getJavaVersionFromJavaHome(binary.getParent.getParent)
    ) match {
      case None => scalacOptions
      case Some(_) if existsReleaseSetting || sameHome => scalacOptions
      case Some(version) =>
        val options: Option[Array[String]] =
          for {
            numVer <- parseJavaVersion(version)
            bloopNumVer <- parseJavaVersion(JavaRuntime.version)
            if (bloopNumVer >= 9 && numVer != bloopNumVer)
          } yield {
            if (bloopNumVer > numVer) {
              scalacOptions ++ releaseFlagForVersion(numVer)
            } else {
              logger.warn(
                s"Bloop is running with ${JavaRuntime.version} but your code requires $version to compile, " +
                  "this might cause some compilation issues when using JDK API unsupported by the Bloop's current JVM version"
              )
              scalacOptions
            }
          }
        options.getOrElse(scalacOptions)
    }
  }

  private def parseJavaVersion(version: String): Option[Int] =
    version.split('-').head.split('.').toList match {
      case "1" :: minor :: _ => Try(minor.toInt).toOption
      case single :: _ => Try(single.toInt).toOption
      case _ => None
    }

  private def getCompilationOptions(
      inputs: CompileInputs,
      logger: Logger,
      newClassesDir: Path
  ): CompileOptions = {
    // Sources are all files
    val sources = inputs.sources.map(path => converter.toVirtualFile(path.underlying))

    val scalacOptions = adjustScalacReleaseOptions(
      scalacOptions = inputs.scalacOptions,
      javacBin = inputs.javacBin,
      logger = logger,
      inputs.scalaInstance.version
    )

    val optionsWithoutFatalWarnings = scalacOptions.filterNot(isFatalWarningOpt)
    val areFatalWarningsEnabled = scalacOptions.length != optionsWithoutFatalWarnings.length

    // Enable fatal warnings in the reporter if they are enabled in the build
    if (areFatalWarningsEnabled)
      inputs.reporter.enableFatalWarnings()

    val needsRtJar = scalacOptions.sliding(2).exists {
      case Array("-release", "8") => true
      case _ => false
    }
    val possibleRtJar =
      if (needsRtJar)
        inputs.javacBin
          .flatMap { binary =>
            Try {
              val javaHome = binary.getParent.getParent
              javaHome.resolve("jre/lib/rt.jar")
            }.toOption
          }
          .filter(_.exists)
          .orElse(RtJarCache.create(JavaRuntime.version, logger))
      else None
    val updatedClasspath = inputs.classpath ++ possibleRtJar
    val classpathVirtual = updatedClasspath.map(path => converter.toVirtualFile(path.underlying))
    CompileOptions
      .create()
      .withClassesDirectory(newClassesDir)
      .withSources(sources)
      .withClasspath(classpathVirtual)
      .withScalacOptions(optionsWithoutFatalWarnings)
      .withJavacOptions(inputs.javacOptions)
      .withOrder(inputs.compileOrder)
  }

  /**
   * Handles successful Best Effort compilation.
   *
   * Does not persist incremental compilation analysis, because as of time of commiting the compiler is not able
   * to always run the necessary phases, nor is zinc adjusted to handle betasty files correctly.
   *
   * Returns a [[bloop.Result.Failed]] with generated CompileProducts and a hash value of inputs and outputs included.
   */
  def handleBestEffortSuccess(
      compileInputs: CompileInputs,
      compileOut: CompileOutPaths,
      elapsed: () => Long,
      reporter: ZincReporter,
      backgroundTasksWhenNewSuccessfulAnalysis: mutable.ListBuffer[CompileBackgroundTasks.Sig],
      previousSuccessfulProblems: List[ProblemPerPhase],
      errorCause: Option[xsbti.CompileFailed],
      shouldAttemptRestartingCompilation: Boolean,
      inputHash: String
  ): Result = {
    val uniqueInputs = compileInputs.uniqueInputs
    val newClassesDir = compileOut.internalNewClassesDir.underlying

    reporter.processEndCompilation(
      previousSuccessfulProblems,
      ch.epfl.scala.bsp.StatusCode.Error,
      None,
      None
    )

    val noOpPreviousResult =
      updatePreviousResultWithRecentClasspathHashes(
        compileInputs.previousResult,
        uniqueInputs
      )

    val products = CompileProducts(
      newClassesDir, // let's not use readonly dir
      newClassesDir,
      noOpPreviousResult,
      noOpPreviousResult,
      Set.empty,
      Map.empty
    )

    val backgroundTasksExecution = new CompileBackgroundTasks {
      def trigger(
          clientClassesObserver: ClientClassesObserver,
          clientReporter: Reporter,
          clientTracer: BraveTracer,
          clientLogger: Logger
      ): Task[Unit] = {
        val clientClassesDir = clientClassesObserver.classesDir
        val successBackgroundTasks =
          deleteClientExternalBestEffortDirTask(clientClassesDir).flatMap { _ =>
            Task.gatherUnordered(
              backgroundTasksWhenNewSuccessfulAnalysis
                .map(f => f(clientClassesDir, clientReporter, clientTracer))
            )
          }
        val allClientSyncTasks = successBackgroundTasks.flatMap { _ =>
          // Only start this task after the previous IO tasks in the external dir are done
          Task {
            // Delete everything outside of betasty and semanticdb
            val deletedCompileProducts =
              BloopClassFileManager.supportedCompileProducts.filter(_ != ".betasty") :+ ".class"
            Files
              .walk(clientClassesDir.underlying)
              .filter(path => Files.isRegularFile(path))
              .filter(path => deletedCompileProducts.exists(path.toString.endsWith(_)))
              .forEach(Files.delete(_))
          }.map(_ => ())
        }

        allClientSyncTasks.doOnFinish(_ => Task(clientReporter.reportEndCompilation()))
      }
    }

    if (shouldAttemptRestartingCompilation) {
      BloopPaths.delete(compileOut.internalNewClassesDir)
    }

    val newHash =
      if (!shouldAttemptRestartingCompilation)
        BestEffortUtils.NonEmptyBestEffortHash(
          inputHash,
          BestEffortUtils.hashOutput(products.newClassesDir)
        )
      else BestEffortUtils.EmptyBestEffortHash
    val failedProblems = findFailedProblems(reporter, errorCause)
    // Note: This is a failed result but for best effort mode, so we record it as best effort
    compileInputs.compilationTraceRecorder.foreach { recorder =>
      val projectName = compileInputs.uniqueInputs.sources.headOption
        .map(_.getParent.getParent.getFileName.toString)
        .getOrElse("unknown")
      val compiledFiles = compileInputs.sources.map(_.syntax).toList
      val diagnostics = reporter.allProblemsPerPhase.flatMap(_.problem).toList
      val artifacts = List(
        CompilationArtifact(
          source = products.newClassesDir.syntax,
          destination = compileOut.externalClassesDir.syntax,
          artifactType = "class"
        )
      )
      
      recorder.endCompilation(
        projectName,
        compiledFiles,
        diagnostics,
        artifacts,
        isNoOp = false,
        "Best Effort Failed"
      )
    }
    Result.Failed(
      failedProblems,
      None,
      elapsed(),
      backgroundTasksExecution,
      Some(BestEffortProducts(products, newHash, shouldAttemptRestartingCompilation))
    )
  }

  def toBackgroundTasks(
      tasks: List[(AbsolutePath, Reporter, BraveTracer) => Task[Unit]]
  ): CompileBackgroundTasks = {
    new CompileBackgroundTasks {
      def trigger(
          clientClassesObserver: ClientClassesObserver,
          clientReporter: Reporter,
          tracer: BraveTracer,
          clientLogger: Logger
      ): Task[Unit] = {
        val clientClassesDir = clientClassesObserver.classesDir
        val backgroundTasks = tasks.map(f => f(clientClassesDir, clientReporter, tracer))
        Task.gatherUnordered(backgroundTasks).memoize.map(_ => ())
      }
    }
  }

  def analysisFrom(prev: PreviousResult): Option[CompileAnalysis] = {
    InterfaceUtil.toOption(prev.analysis())
  }

  /**
   * Returns the problems (infos/warnings) that were generated in the last
   * successful incremental compilation. These problems are material for
   * the correct handling of compiler reporters since they might be stateful
   * with the clients (e.g. BSP reporter).
   */
  def previousProblemsFromSuccessfulCompilation(
      analysis: Option[CompileAnalysis]
  ): List[ProblemPerPhase] = {
    analysis.map(prev => AnalysisUtils.problemsFrom(prev)).getOrElse(Nil)
  }

  /**
   * the problems (errors/warnings/infos) that were generated in the
   * last incremental compilation, be it successful or not. See
   * [[previousProblemsFromSuccessfulCompilation]] for an explanation of why
   * these problems are important for the bloop compilation.
   *
   * @see [[previousProblemsFromSuccessfulCompilation]]
   */
  def previousProblemsFromResult(
      result: Compiler.Result,
      previousSuccessfulProblems: List[ProblemPerPhase]
  ): List[ProblemPerPhase] = {
    result match {
      case f: Compiler.Result.Failed => f.problems
      case c: Compiler.Result.Cancelled => c.problems
      case _: Compiler.Result.Success => previousSuccessfulProblems
      case _ => Nil
    }
  }

  /**
   * Update the previous result with the most recent classpath hashes.
   *
   * The incremental compiler has two mechanisms to ascertain if it has to
   * recompile code or can skip recompilation (what it's traditionally called
   * as a no-op compile). The first phase checks if classpath hashes are the
   * same. If they are, it's a no-op compile, if they are not then it passes to
   * the second phase which does an expensive classpath computation to better
   * decide if a recompilation is needed.
   *
   * This last step can be expensive when there are lots of projects in a
   * build and even more so when these projects produce no-op compiles. This
   * method makes sure we update the classpath hash if Zinc finds a change in
   * the classpath and still decides it's a no-op compile. This prevents
   * subsequent no-op compiles from paying the price for the same expensive
   * classpath check.
   */
  def updatePreviousResultWithRecentClasspathHashes(
      previous: PreviousResult,
      uniqueInputs: UniqueCompileInputs
  ): PreviousResult = {
    val newClasspathHashes =
      BloopLookup.filterOutDirsFromHashedClasspath(uniqueInputs.classpath)
    val newSetup = InterfaceUtil
      .toOption(previous.setup())
      .map(s => s.withOptions(s.options().withClasspathHash(newClasspathHashes.toArray)))
    previous.withSetup(InterfaceUtil.toOptional(newSetup))
  }

  /**
   * Change the paths of the class files inside the analysis.
   *
   * As compiler isolation requires every process to write to an independent
   * classes directory, while still sourcing the previous class files from a
   * read-only classes directory, this method has to ensure that the next
   * user of this analysis sees that all products come from the same directory,
   * which is the new classes directory we've written to.
   *
   * Up in the bloop call stack, we make sure that we spawn a process that
   * copies all class files from the read-only classes directory to the new
   * classes directory so that the new paths in the analysis exist in the file
   * system.
   */
  def rebaseAnalysisClassFiles(
      analysis0: CompileAnalysis,
      origin: Path,
      target: Path,
      sourceFilesWithFatalWarnings: scala.collection.Set[File]
  ): Analysis = {
    // Cast to the only internal analysis that we support
    val analysis = analysis0.asInstanceOf[Analysis]
    def rebase(file: VirtualFileRef): VirtualFileRef = {

      val filePath = converter.toPath(file).toAbsolutePath()
      if (!filePath.startsWith(origin)) file
      else {
        // Hash for class file is the same because the copy duplicates metadata
        val path = target.resolve(origin.relativize(filePath))
        converter.toVirtualFile(path)
      }
    }

    val newStamps = {
      import sbt.internal.inc.Stamps
      val oldStamps = analysis.stamps
      // Use empty stamps for files that have fatal warnings so that next compile recompiles them
      val rebasedSources = oldStamps.sources.map {
        case t @ (virtualFile, _) =>
          val file = converter.toPath(virtualFile).toFile()
          // Assumes file in reported diagnostic matches path in here
          val fileHasFatalWarnings = sourceFilesWithFatalWarnings.contains(file)
          if (!fileHasFatalWarnings) t
          else virtualFile -> BloopStamps.emptyStamps
      }
      val rebasedProducts = oldStamps.products.map {
        case t @ (file, _) =>
          val rebased = rebase(file)
          if (rebased == file) t else rebased -> t._2
      }
      // Changes the paths associated with the class file paths
      Stamps(rebasedProducts, rebasedSources, oldStamps.libraries)
    }

    val newRelations = {
      import sbt.internal.inc.bloop.ZincInternals
      val oldRelations = analysis.relations
      // Changes the source to class files relation
      ZincInternals.copyRelations(oldRelations, rebase(_))
    }

    analysis.copy(stamps = newStamps, relations = newRelations)
  }

  def persist(
      storeFile: AbsolutePath,
      analysis: CompileAnalysis,
      setup: MiniSetup,
      tracer: BraveTracer,
      logger: Logger
  ): Unit = try {
    val label = s"Writing analysis to ${storeFile.syntax}..."
    tracer.trace(label) { _ =>
      if (analysis == Analysis.Empty || analysis.equals(Analysis.empty)) {
        logger.debug(s"Skipping analysis persistence to ${storeFile.syntax}, analysis is empty")
      } else {
        logger.debug(label)
        FileAnalysisStore.binary(storeFile.toFile).set(ConcreteAnalysisContents(analysis, setup))
        logger.debug(s"Wrote analysis to ${storeFile.syntax}...")
      }
    }
  } catch {
    case t @ (_: StackOverflowError | _: NoClassDefFoundError) =>
      val msg =
        "Encountered a error while persisting zinc analysis. Please report an issue in sbt/zinc repository."
      logger.error(s"${msg}:\n${t.getStackTrace().mkString("\n")}")
      throw new CompileFailed(new Array(0), msg, new Array(0), None, t)
  }

  // Deletes all previous best-effort artifacts to get rid of all of the outdated ones.
  // Since best effort compilation is not affected by incremental compilation,
  // all relevant files are always produced by the compiler. Because of this,
  // we can always delete all previous files and copy newly created ones
  // without losing anything in the process.
  def deleteClientExternalBestEffortDirTask(clientClassesDir: AbsolutePath) = {
    val clientExternalBestEffortDir =
      clientClassesDir.underlying.resolve("META-INF/best-effort")
    Task {
      if (Files.exists(clientExternalBestEffortDir)) {
        BloopPaths.delete(AbsolutePath(clientExternalBestEffortDir))
      }
      ()
    }.memoize
  }
}
