package bloop

import java.util.Optional
import java.util.UUID
import java.io.File
import java.util.concurrent.{Executor, ConcurrentHashMap}
import java.util.UUID
import java.nio.file.{Files, Path}

import bloop.io.{Paths => BloopPaths}
import bloop.io.AbsolutePath
import bloop.io.ParallelOps
import bloop.io.ParallelOps.CopyMode
import bloop.tracing.BraveTracer
import bloop.logging.{ObservedLogger, Logger}
import bloop.reporter.{ProblemPerPhase, ZincReporter}
import bloop.util.{AnalysisUtils, CacheHashCode}

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

case class CompileInputs(
    scalaInstance: ScalaInstance,
    compilerCache: CompilerCache,
    sources: Array[AbsolutePath],
    classpath: Array[AbsolutePath],
    oracleInputs: CompilerOracle.Inputs,
    store: IRStore,
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
    invalidatedClassFilesInDependentProjects: Set[File]
)

case class CompileOutPaths(
    analysisOut: AbsolutePath,
    externalClassesDir: AbsolutePath,
    internalReadOnlyClassesDir: AbsolutePath
) {
  lazy val internalNewClassesDir: AbsolutePath = {
    val classesDir = internalReadOnlyClassesDir.underlying
    val parentDir = classesDir.getParent()
    val classesName = externalClassesDir.underlying.getFileName()
    val newClassesName = s"${classesName}-${UUID.randomUUID}"
    AbsolutePath(
      Files.createDirectories(parentDir.resolve(newClassesName)).toRealPath()
    )
  }
}

object Compiler {
  private implicit val filter = bloop.logging.DebugFilter.Compilation
  private final class ZincClasspathEntryLookup(results: Map[File, PreviousResult])
      extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] = {
      InterfaceUtil.toOptional(results.get(classpathEntry)).flatMap(_.analysis())
    }

    override def definesClass(classpathEntry: File): DefinesClass = {
      Locate.definesClass(classpathEntry)
    }
  }

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
        inputs: CompilerOracle.Inputs,
        reporter: ZincReporter,
        products: CompileProducts,
        elapsed: Long,
        backgroundTasks: CompileBackgroundTasks,
        isNoOp: Boolean
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
        case s @ (Success(_, _, _, _, _, _) | Empty) => Some(s)
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

  private final val supportedCompileProducts = List(".sjsir", ".nir", ".tasty")
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

    val copiedPathsFromNewClassesDir = new mutable.HashSet[Path]()
    val allInvalidatedClassFilesForProject = new mutable.HashSet[File]()
    val allInvalidatedExtraCompileProducts = new mutable.HashSet[File]()
    val backgroundTasksWhenNewSuccessfulAnalysis =
      new mutable.ListBuffer[(AbsolutePath, BraveTracer) => Task[Unit]]()
    val backgroundTasksForFailedCompilation =
      new mutable.ListBuffer[(AbsolutePath, BraveTracer) => Task[Unit]]()
    def getClassFileManager(): ClassFileManager = {
      new ClassFileManager {
        import sbt.io.IO
        private[this] val invalidatedClassFilesInLastRun = new mutable.HashSet[File]
        def delete(classes: Array[File]): Unit = {
          // Add to the blacklist so that we never copy them
          allInvalidatedClassFilesForProject.++=(classes)
          val invalidatedExtraCompileProducts = classes.flatMap { classFile =>
            supportedCompileProducts.flatMap { supportedProductSuffix =>
              val productName = classFile.getName().stripSuffix(".class") + supportedProductSuffix
              val productAssociatedToClassFile = new File(classFile.getParentFile, productName)
              if (!productAssociatedToClassFile.exists()) Nil
              else List(productAssociatedToClassFile)
            }
          }
          allInvalidatedExtraCompileProducts.++=(invalidatedExtraCompileProducts)
        }

        import compileInputs.invalidatedClassFilesInDependentProjects
        def invalidatedClassFiles(): Array[File] = {
          // Invalidate class files from dependent projects + those invalidated in last incremental run
          val invalidatedClassFilesForRun = allInvalidatedClassFilesForProject.iterator.filter {
            f =>
              // A safety measure in case Zinc does not get a complete list of generated classes
              val filePath = f.getAbsolutePath
              if (!filePath.startsWith(readOnlyClassesDirPath)) true
              else {
                // Filter out invalidated files in read-only directories that exist in new classes directory
                val newFile = new File(filePath.replace(readOnlyClassesDirPath, newClassesDirPath))
                !newFile.exists
              }
          }

          (invalidatedClassFilesInDependentProjects ++ invalidatedClassFilesForRun).toArray
        }

        def generated(generatedClassFiles: Array[File]): Unit = {
          generatedClassFiles.foreach { generatedClassFile =>
            val newClassFile = generatedClassFile.getAbsolutePath
            val rebasedClassFile =
              new File(newClassFile.replace(newClassesDirPath, readOnlyClassesDirPath))
            allInvalidatedClassFilesForProject.-=(rebasedClassFile)
            supportedCompileProducts.foreach { supportedProductSuffix =>
              val productName = rebasedClassFile
                .getName()
                .stripSuffix(".class") + supportedProductSuffix
              val productAssociatedToClassFile =
                new File(rebasedClassFile.getParentFile, productName)
              if (productAssociatedToClassFile.exists())
                allInvalidatedExtraCompileProducts.-=(productAssociatedToClassFile)
            }
          }
        }

        def complete(success: Boolean): Unit = {
          if (success) {
            // Schedule copying compilation products to visible classes directory
            backgroundTasksWhenNewSuccessfulAnalysis.+=(
              (clientExternalClassesDir: AbsolutePath, clientTracer: BraveTracer) => {
                clientTracer.traceTask("copy new products to external classes dir") { _ =>
                  val config = ParallelOps
                    .CopyConfiguration(5, CopyMode.ReplaceExisting, Set.empty)
                  ParallelOps
                    .copyDirectories(config)(
                      newClassesDir,
                      clientExternalClassesDir.underlying,
                      compileInputs.ioScheduler,
                      compileInputs.logger
                    )
                    .map { walked =>
                      copiedPathsFromNewClassesDir.++=(walked.target)
                      ()
                    }
                }
              }
            )
          } else {
            // Delete all compilation products generated in the new classes directory
            val deleteNewDir = Task { BloopPaths.delete(AbsolutePath(newClassesDir)); () }.memoize
            backgroundTasksForFailedCompilation.+=(
              (clientExternalClassesDir: AbsolutePath, clientTracer: BraveTracer) => {
                clientTracer.traceTask("delete class files after")(_ => deleteNewDir)
              }
            )

            backgroundTasksForFailedCompilation.+=(
              (clientExternalClassesDir: AbsolutePath, clientTracer: BraveTracer) => {
                clientTracer.traceTask("populate external classes dir as it's empty") { _ =>
                  Task {
                    if (!BloopPaths.isDirectoryEmpty(clientExternalClassesDir)) Task.unit
                    else {
                      if (BloopPaths.isDirectoryEmpty(compileOut.internalReadOnlyClassesDir)) {
                        Task.unit
                      } else {
                        // Prepopulate external classes dir even though compilation failed
                        val config = ParallelOps.CopyConfiguration(1, CopyMode.NoReplace, Set.empty)
                        ParallelOps
                          .copyDirectories(config)(
                            readOnlyClassesDir,
                            clientExternalClassesDir.underlying,
                            compileInputs.ioScheduler,
                            compileInputs.logger
                          )
                          .map(_ => ())
                      }
                    }
                  }.flatten
                }
              }
            )
          }
        }
      }
    }

    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getCompilationOptions(inputs: CompileInputs): CompileOptions = {
      val sources = inputs.sources // Sources are all files
      val classpath = inputs.classpath.map(_.toFile)

      CompileOptions
        .create()
        .withClassesDirectory(newClassesDir.toFile)
        .withSources(sources.map(_.toFile))
        .withClasspath(classpath)
        .withStore(inputs.store)
        .withScalacOptions(inputs.scalacOptions)
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

      val lookup = new ZincClasspathEntryLookup(results)
      val reporter = compileInputs.reporter
      val compilerCache = new FreshCompilerCache
      val cacheFile = compileInputs.baseDirectory.resolve("cache").toFile
      val incOptions = {
        val disableIncremental = java.lang.Boolean.getBoolean("bloop.zinc.disabled")
        // Don't customize class file manager bc we pass our own to the zinc APIs directly
        IncOptions.create().withEnabled(!disableIncremental)
      }

      val progress = Optional.of[CompileProgress](new BloopProgress(reporter, cancelPromise))
      val setup =
        Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
      // We only set the pickle promise here, but the java signal is set in `BloopHighLevelCompiler`
      compileInputs.mode match {
        case p: CompileMode.Pipelined => setup.withIrPromise(p.irs)
        case pp: CompileMode.ParallelAndPipelined => setup.withIrPromise(pp.irs)
        case _ => setup
      }
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
    val reporter = compileInputs.reporter

    def cancel(): Unit = {
      // Synchronize because for some weird reason Monix seems to be running this twice
      cancelPromise.synchronized {
        // Avoid illegal state exception if client cancellation promise is completed
        if (!cancelPromise.isCompleted) {
          compileInputs.cancelPromise.success(())
        }
      }

      // Always report the compilation of a project no matter if it's completed
      reporter.reportCancelledCompilation()
    }

    val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
    val previousSuccessfulProblems = previousProblemsFromSuccessfulCompilation(previousAnalysis)
    val previousProblems =
      previousProblemsFromResult(compileInputs.previousCompilerResult, previousSuccessfulProblems)

    val mode = compileInputs.mode
    val manager = getClassFileManager()
    val oracleInputs = compileInputs.oracleInputs
    reporter.reportStartCompilation(previousProblems)
    BloopZincCompiler
      .compile(inputs, mode, reporter, logger, oracleInputs, manager, tracer)
      .materialize
      .doOnCancel(Task(cancel()))
      .map {
        case Success(result) =>
          // Report end of compilation only after we have reported all warnings from previous runs
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Ok)

          // Compute the results we should use for dependent compilations and new compilation runs
          val resultForDependentCompilationsInSameRun =
            PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
          val analysisForFutureCompilationRuns =
            rebaseAnalysisClassFiles(result.analysis(), readOnlyClassesDir, newClassesDir)
          val resultForFutureCompilationRuns = resultForDependentCompilationsInSameRun.withAnalysis(
            Optional.of(analysisForFutureCompilationRuns)
          )

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
                  compileInputs.logger
                )
                lastCopy.map(fs => ())
              }.flatten
            }
          }

          val isNoOp = previousAnalysis.exists(_ == result.analysis())
          if (isNoOp) {
            // If no-op, return previous result
            val products = CompileProducts(
              readOnlyClassesDir,
              readOnlyClassesDir,
              compileInputs.previousResult,
              compileInputs.previousResult,
              Set()
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
              compileInputs.oracleInputs,
              compileInputs.reporter,
              products,
              elapsed,
              backgroundTasks,
              isNoOp
            )
          } else {
            val persistTask = {
              val persistOut = compileOut.analysisOut
              val setup = result.setup
              val analysis = analysisForFutureCompilationRuns
              // Important to memoize it, it's triggered by different clients
              Task(persist(persistOut, analysis, setup, tracer, logger)).memoize
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
              allInvalidated.toSet
            )

            Result.Success(
              compileInputs.oracleInputs,
              compileInputs.reporter,
              products,
              elapsed,
              backgroundTasksExecution,
              isNoOp
            )
          }
        case Failure(_: xsbti.CompileCancelled) =>
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Cancelled)
          val backgroundTasks =
            triggerTaskForBackground(backgroundTasksForFailedCompilation.toList)
          Result.Cancelled(reporter.allProblemsPerPhase.toList, elapsed, backgroundTasks)
        case Failure(cause) =>
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Error)

          cause match {
            case f: StopPipelining => Result.Blocked(f.failedProjectNames)
            case f: xsbti.CompileFailed =>
              // We cannot assert reporter.problems == f.problems, so we aggregate them together
              val reportedProblems = reporter.allProblemsPerPhase.toList
              val rawProblemsFromReporter = reportedProblems.iterator.map(_.problem).toSet
              val newProblems = f.problems().flatMap { p =>
                if (rawProblemsFromReporter.contains(p)) Nil
                else List(ProblemPerPhase(p, None))
              }
              val failedProblems = reportedProblems ++ newProblems.toList
              val backgroundTasks =
                triggerTaskForBackground(backgroundTasksForFailedCompilation.toList)
              Result.Failed(failedProblems, None, elapsed, backgroundTasks)
            case t: Throwable =>
              t.printStackTrace()
              val backgroundTasks =
                triggerTaskForBackground(backgroundTasksForFailedCompilation.toList)
              Result.Failed(Nil, Some(t), elapsed, backgroundTasks)
          }
      }
  }

  def triggerTaskForBackground(
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
      newClassesDir: Path
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
      val oldProducts = oldStamps.products.map {
        case t @ (file, _) =>
          val rebased = rebase(file)
          if (rebased == file) t else rebased -> t._2
      }
      // Changes the paths associated with the class file paths
      Stamps(oldProducts, oldStamps.sources, oldStamps.binaries)
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
