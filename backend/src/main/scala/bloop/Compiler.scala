package bloop

import java.util.Optional
import java.io.File
import java.util.concurrent.{Executor, ConcurrentHashMap}
import java.nio.file.{Files, Path}

import bloop.io.{Paths => BloopPaths}
import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode
import bloop.tracing.BraveTracer
import bloop.logging.{ObservedLogger, Logger}
import bloop.reporter.{ProblemPerPhase, ZincReporter}
import bloop.util.{AnalysisUtils, UUIDUtil, CacheHashCode}
import bloop.CompileMode.Pipelined
import bloop.CompileMode.Sequential

import xsbti.compile._
import xsbti.T2

import sbt.util.InterfaceUtil
import sbt.internal.inc.Analysis
import sbt.internal.inc.bloop.BloopZincCompiler
import sbt.internal.inc.{FreshCompilerCache, InitialChanges, Locate}
import sbt.internal.inc.bloop.internal.StopPipelining
import sbt.internal.inc.{ConcreteAnalysisContents, FileAnalysisStore}

import scala.concurrent.Promise
import scala.collection.mutable

import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.CancelableFuture
import monix.execution.ExecutionModel
import sbt.internal.inc.bloop.internal.BloopStamps
import sbt.internal.inc.bloop.internal.BloopLookup

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
    compileOrder: CompileOrder,
    classpathOptions: ClasspathOptions,
    previousResult: PreviousResult,
    previousCompilerResult: Compiler.Result,
    reporter: ZincReporter,
    logger: ObservedLogger[Logger],
    mode: CompileMode,
    dependentResults: Map[File, PreviousResult],
    cancelPromise: Promise[Unit],
    tracer: BraveTracer,
    ioScheduler: Scheduler,
    ioExecutor: Executor,
    invalidatedClassFilesInDependentProjects: Set[File],
    generatedClassFilePathsInDependentProjects: Map[String, File]
)

case class CompileOutPaths(
    analysisOut: AbsolutePath,
    genericClassesDir: AbsolutePath,
    externalClassesDir: AbsolutePath,
    internalReadOnlyClassesDir: AbsolutePath
) {
  // Don't change the internals of this method without updating how they are cleaned up
  private def createInternalNewDir(generateDirName: String => String): AbsolutePath = {
    val parentInternalDir =
      CompileOutPaths.createInternalClassesRootDir(genericClassesDir).underlying

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
  def createInternalClassesRootDir(projectGenericClassesDir: AbsolutePath): AbsolutePath = {
    AbsolutePath(
      Files.createDirectories(
        projectGenericClassesDir.underlying.getParent().resolve("bloop-internal-classes")
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
  private implicit val filter = bloop.logging.DebugFilter.Compilation
  private final class BloopProgress(
      reporter: ZincReporter,
      cancelPromise: Promise[Unit]
  ) extends CompileProgress {
    override def startUnit(phase: String, unitPath: String): Unit = {
      reporter.reportNextPhase(phase, new java.io.File(unitPath))
    }

    override def advance(current: Int, total: Int): Boolean = {
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
    final case class GlobalError(problem: String) extends Result with CacheHashCode

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
        backgroundTasks: CompileBackgroundTasks
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
        case f @ (Failed(_, _, _, _) | Cancelled(_, _, _) | Blocked(_) | GlobalError(_)) => Some(f)
        case _ => None
      }
    }
  }

  def compile(compileInputs: CompileInputs): Task[Result] = {
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

    logger.debug(s"External classes directory ${externalClassesDirPath}")
    logger.debug(s"Read-only classes directory ${readOnlyClassesDirPath}")
    logger.debug(s"New rw classes directory ${newClassesDirPath}")

    val allGeneratedRelativeClassFilePaths = new mutable.HashMap[String, File]()
    val copiedPathsFromNewClassesDir = new mutable.HashSet[Path]()
    val allInvalidatedClassFilesForProject = new mutable.HashSet[File]()
    val allInvalidatedExtraCompileProducts = new mutable.HashSet[File]()

    val backgroundTasksWhenNewSuccessfulAnalysis =
      new mutable.ListBuffer[CompileBackgroundTasks.Sig]()
    val backgroundTasksForFailedCompilation =
      new mutable.ListBuffer[CompileBackgroundTasks.Sig]()

    def newFileManager: ClassFileManager = {
      new BloopClassFileManager(
        compileInputs,
        compileOut,
        allGeneratedRelativeClassFilePaths,
        copiedPathsFromNewClassesDir,
        allInvalidatedClassFilesForProject,
        allInvalidatedExtraCompileProducts,
        backgroundTasksWhenNewSuccessfulAnalysis,
        backgroundTasksForFailedCompilation
      )
    }

    var isFatalWarningsEnabled: Boolean = false
    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getCompilationOptions(inputs: CompileInputs): CompileOptions = {
      val sources = inputs.sources // Sources are all files
      val classpath = inputs.classpath.map(_.toFile)
      val optionsWithoutFatalWarnings = inputs.scalacOptions.flatMap { option =>
        if (option != "-Xfatal-warnings") List(option)
        else {
          if (!isFatalWarningsEnabled) isFatalWarningsEnabled = true
          Nil
        }
      }

      // Enable fatal warnings in the reporter if they are enabled in the build
      if (isFatalWarningsEnabled)
        inputs.reporter.enableFatalWarnings()

      CompileOptions
        .create()
        .withClassesDirectory(newClassesDir.toFile)
        .withSources(sources.map(_.toFile))
        .withClasspath(classpath)
        .withScalacOptions(optionsWithoutFatalWarnings)
        .withJavacOptions(inputs.javacOptions)
        .withClasspathOptions(inputs.classpathOptions)
        .withOrder(inputs.compileOrder)
    }

    def getSetup(compileInputs: CompileInputs): Setup = {
      val skip = false
      val empty = Array.empty[T2[String, String]]
      val results = compileInputs.dependentResults.+(
        readOnlyClassesDir.toFile -> compileInputs.previousResult,
        newClassesDir.toFile -> compileInputs.previousResult
      )

      val lookup = new BloopClasspathEntryLookup(results, compileInputs.uniqueInputs.classpath)
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

    def runAggregateTasks(tasks: List[Task[Unit]]): CancelableFuture[Unit] = {
      Task
        .gatherUnordered(tasks)
        .map(_ => ())
        .runAsync(compileInputs.ioScheduler)
    }

    val start = System.nanoTime()
    val scalaInstance = compileInputs.scalaInstance
    val classpathOptions = compileInputs.classpathOptions
    val compilers = compileInputs.compilerCache.get(scalaInstance)
    val inputs = tracer.trace("creating zinc inputs")(_ => getInputs(compilers))

    // We don't need nanosecond granularity, we're happy with milliseconds
    def elapsed: Long = ((System.nanoTime() - start).toDouble / 1e6).toLong

    import ch.epfl.scala.bsp
    import scala.util.{Success, Failure}
    val mode = compileInputs.mode
    val reporter = compileInputs.reporter

    def cancel(): Unit = {
      // Complete all pending promises when compilation is cancelled
      logger.debug(s"Cancelling compilation from ${readOnlyClassesDirPath} to ${newClassesDirPath}")
      compileInputs.cancelPromise.trySuccess(())
      mode match {
        case _: Sequential => ()
        case Pipelined(completeJava, finishedCompilation, _, _, _) =>
          completeJava.trySuccess(())
          finishedCompilation.tryFailure(CompileExceptions.FailedOrCancelledPromise)
      }

      // Always report the compilation of a project no matter if it's completed
      reporter.reportCancelledCompilation()
    }

    val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
    val previousSuccessfulProblems = previousProblemsFromSuccessfulCompilation(previousAnalysis)
    val previousProblems =
      previousProblemsFromResult(compileInputs.previousCompilerResult, previousSuccessfulProblems)

    def handleCancellation: Compiler.Result = {
      reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Cancelled)
      val backgroundTasks =
        toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
      Result.Cancelled(reporter.allProblemsPerPhase.toList, elapsed, backgroundTasks)
    }

    val uniqueInputs = compileInputs.uniqueInputs
    reporter.reportStartCompilation(previousProblems)
    BloopZincCompiler
      .compile(inputs, mode, reporter, logger, uniqueInputs, newFileManager, cancelPromise, tracer)
      .materialize
      .doOnCancel(Task(cancel()))
      .map {
        case Success(_) if cancelPromise.isCompleted => handleCancellation
        case Success(result) =>
          // Report end of compilation only after we have reported all warnings from previous runs
          val sourcesWithFatal = reporter.getSourceFilesWithFatalWarnings
          val reportedFatalWarnings = isFatalWarningsEnabled && sourcesWithFatal.nonEmpty
          val code = if (reportedFatalWarnings) bsp.StatusCode.Error else bsp.StatusCode.Ok
          reporter.reportEndCompilation(previousSuccessfulProblems, code)

          // Compute the results we should use for dependent compilations and new compilation runs
          val resultForDependentCompilationsInSameRun =
            PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
          val analysis = result.analysis()

          def updateExternalClassesDirWithReadOnly(
              clientClassesDir: AbsolutePath,
              clientTracer: BraveTracer
          ): Task[Unit] = {
            clientTracer.traceTask("update external classes dir with read-only") { _ =>
              Task {
                // Attention: blacklist must be computed inside the task!
                val invalidatedClassFiles =
                  allInvalidatedClassFilesForProject.iterator.map(_.toPath).toSet
                val invalidatedExtraProducts =
                  allInvalidatedExtraCompileProducts.iterator.map(_.toPath).toSet
                val invalidatedInThisProject = invalidatedClassFiles ++ invalidatedExtraProducts
                val blacklist = invalidatedInThisProject ++ copiedPathsFromNewClassesDir.iterator
                val config =
                  ParallelOps.CopyConfiguration(5, CopyMode.ReplaceIfMetadataMismatch, blacklist)
                val lastCopy = ParallelOps.copyDirectories(config)(
                  readOnlyClassesDir,
                  externalClassesDir,
                  compileInputs.ioScheduler,
                  compileInputs.logger,
                  enableCancellation = false
                )
                lastCopy.map(fs => ())
              }.flatten
            }
          }

          val definedMacroSymbols = mode.oracle.collectDefinedMacroSymbols
          val isNoOp = previousAnalysis.contains(analysis)
          if (isNoOp) {
            // If no-op, return previous result with updated classpath hashes
            val noOpPreviousResult = {
              updatePreviousResultWithRecentClasspathHashes(
                compileInputs.previousResult,
                uniqueInputs
              )
            }

            val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
            val products = CompileProducts(
              readOnlyClassesDir,
              readOnlyClassesDir,
              noOpPreviousResult,
              noOpPreviousResult,
              Set(),
              Map.empty,
              definedMacroSymbols
            )

            val backgroundTasks = new CompileBackgroundTasks {
              def trigger(clientClassesDir: AbsolutePath, clientTracer: BraveTracer): Task[Unit] = {
                Task.mapBoth(
                  Task(BloopPaths.delete(AbsolutePath(newClassesDir))),
                  updateExternalClassesDirWithReadOnly(clientClassesDir, clientTracer)
                )((_: Unit, _: Unit) => ())
              }
            }

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
            val analysisForFutureCompilationRuns = {
              rebaseAnalysisClassFiles(
                analysis,
                readOnlyClassesDir,
                newClassesDir,
                sourcesWithFatal
              )
            }

            val resultForFutureCompilationRuns = {
              resultForDependentCompilationsInSameRun.withAnalysis(
                Optional.of(analysisForFutureCompilationRuns)
              )
            }

            val persistTask = {
              val persistOut = compileOut.analysisOut
              val setup = result.setup
              // Important to memoize it, it's triggered by different clients
              Task(persist(persistOut, analysisForFutureCompilationRuns, setup, tracer, logger)).memoize
            }

            // Delete all those class files that were invalidated in the external classes dir
            val allInvalidated = allInvalidatedClassFilesForProject ++ allInvalidatedExtraCompileProducts

            // Schedule the tasks to run concurrently after the compilation end
            val backgroundTasksExecution = new CompileBackgroundTasks {
              def trigger(clientClassesDir: AbsolutePath, clientTracer: BraveTracer): Task[Unit] = {
                val clientClassesDirPath = clientClassesDir.toString
                val successBackgroundTasks =
                  backgroundTasksWhenNewSuccessfulAnalysis.map(
                    f => f(clientClassesDir, clientTracer)
                  )
                val initialTasks = persistTask :: successBackgroundTasks.toList
                Task.gatherUnordered(initialTasks).flatMap { _ =>
                  // Only start these tasks after the previous IO tasks in the external dir are done
                  val firstTask =
                    updateExternalClassesDirWithReadOnly(clientClassesDir, clientTracer)
                  val secondTask = Task {
                    allInvalidated.foreach { f =>
                      val path = AbsolutePath(f.toPath)
                      val syntax = path.syntax
                      if (syntax.startsWith(readOnlyClassesDirPath)) {
                        val rebasedFile = AbsolutePath(
                          syntax.replace(readOnlyClassesDirPath, clientClassesDirPath)
                        )
                        if (rebasedFile.exists) {
                          Files.delete(rebasedFile.underlying)
                        }
                      }
                    }
                  }
                  Task.gatherUnordered(List(firstTask, secondTask)).map(_ => ())
                }
              }
            }

            val products = CompileProducts(
              readOnlyClassesDir,
              newClassesDir,
              resultForDependentCompilationsInSameRun,
              resultForFutureCompilationRuns,
              allInvalidated.toSet,
              allGeneratedRelativeClassFilePaths.toMap,
              definedMacroSymbols
            )

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
        case Failure(_: xsbti.CompileCancelled) => handleCancellation
        case Failure(cause) =>
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Error)

          cause match {
            case f: StopPipelining => Result.Blocked(f.failedProjectNames)
            case f: xsbti.CompileFailed =>
              // We cannot guarantee reporter.problems == f.problems, so we aggregate them together
              val reportedProblems = reporter.allProblemsPerPhase.toList
              val rawProblemsFromReporter = reportedProblems.iterator.map(_.problem).toSet
              val newProblems = f.problems().flatMap { p =>
                if (rawProblemsFromReporter.contains(p)) Nil
                else List(ProblemPerPhase(p, None))
              }
              val failedProblems = reportedProblems ++ newProblems.toList
              val backgroundTasks =
                toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
              Result.Failed(failedProblems, None, elapsed, backgroundTasks)
            case t: Throwable =>
              t.printStackTrace()
              val backgroundTasks =
                toBackgroundTasks(backgroundTasksForFailedCompilation.toList)
              Result.Failed(Nil, Some(t), elapsed, backgroundTasks)
          }
      }
  }

  def toBackgroundTasks(
      tasks: List[(AbsolutePath, BraveTracer) => Task[Unit]]
  ): CompileBackgroundTasks = {
    new CompileBackgroundTasks {
      def trigger(clientClassesDir: AbsolutePath, tracer: BraveTracer): Task[Unit] = {
        val backgroundTasks = tasks.map(f => f(clientClassesDir, tracer))
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
   * Update the previous reuslt with the most recent classpath hashes.
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
      analysis0: xsbti.compile.CompileAnalysis,
      readOnlyClassesDir: Path,
      newClassesDir: Path,
      sourceFilesWithFatalWarnings: scala.collection.Set[File]
  ): Analysis = {
    // Cast to the only internal analysis that we support
    val analysis = analysis0.asInstanceOf[Analysis]
    def rebase(file: File): File = {
      val filePath = file.toPath.toAbsolutePath
      if (!filePath.startsWith(readOnlyClassesDir)) file
      else {
        // Hash for class file is the same because the copy duplicates metadata
        newClassesDir.resolve(readOnlyClassesDir.relativize(filePath)).toFile
      }
    }

    val newStamps = {
      import sbt.internal.inc.Stamps
      val oldStamps = analysis.stamps
      // Use empty stamps for files that have fatal warnings so that next compile recompiles them
      val rebasedSources = oldStamps.sources.map {
        case t @ (file, _) =>
          // Assumes file in reported diagnostic matches path in here
          val fileHasFatalWarnings = sourceFilesWithFatalWarnings.contains(file)
          if (!fileHasFatalWarnings) t
          else file -> BloopStamps.emptyStampFor(file)
      }
      val rebasedProducts = oldStamps.products.map {
        case t @ (file, _) =>
          val rebased = rebase(file)
          if (rebased == file) t else rebased -> t._2
      }
      // Changes the paths associated with the class file paths
      Stamps(rebasedProducts, rebasedSources, oldStamps.binaries)
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
  ): Unit = {
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
  }
}
