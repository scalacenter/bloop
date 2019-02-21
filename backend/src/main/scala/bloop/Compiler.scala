package bloop

import java.util.Optional
import java.io.File
import java.util.concurrent.Executor
import java.util.UUID
import java.nio.file.{Files, Path}

import bloop.io.AbsolutePath
import bloop.io.ParallelOps
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
    readOnlyClassesDir: AbsolutePath,
    visibleClassesDir: AbsolutePath,
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
    scheduler: Scheduler,
    executor: Executor
)

object Compiler {
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
        reporter: ZincReporter,
        previous: PreviousResult,
        elapsed: Long,
        backgroundTasks: CancelableFuture[Unit]
    ) extends Result
        with CacheHashCode

    final case class Failed(
        problems: List[ProblemPerPhase],
        t: Option[Throwable],
        elapsed: Long,
        backgroundTasks: CancelableFuture[Unit]
    ) extends Result
        with CacheHashCode

    final case class Cancelled(
        problems: List[ProblemPerPhase],
        elapsed: Long,
        backgroundTasks: CancelableFuture[Unit]
    ) extends Result
        with CacheHashCode

    object Ok {
      def unapply(result: Result): Option[Result] = result match {
        case s @ (Success(_, _, _, _) | Empty) => Some(s)
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
    val tracer = compileInputs.tracer
    val readOnlyClassesDir = {
      val classesDir = compileInputs.readOnlyClassesDir.underlying
      java.nio.file.Files.createDirectories(classesDir).toRealPath()
    }

    val userClassesDir = {
      // Ensure that the visible user classes directory exists and that we use its real path
      val classesDir = compileInputs.visibleClassesDir.underlying
      require(Files.isDirectory(classesDir))
      classesDir.toRealPath()
    }

    val newClassesDir: Path = {
      // Create a classes directory only for this compilation run
      val parentDir = readOnlyClassesDir.getParent
      val classesName = readOnlyClassesDir.getFileName()
      Files
        .createDirectories(parentDir.resolve(s"${classesName}.rw-${UUID.randomUUID}"))
        .toRealPath()
    }

    val backgroundTasksWhenNewAnalysis = new mutable.ListBuffer[Task[Unit]]()
    val backgroundTasksForFailedCompilation = new mutable.ListBuffer[Task[Unit]]()
    def getClassFileManager(): ClassFileManager = {
      new ClassFileManager {
        import sbt.io.IO
        private[this] val invalidatedClassFilesInLastRun = new mutable.HashSet[File]
        def delete(classes: Array[File]): Unit = {
          invalidatedClassFilesInLastRun.clear()
          invalidatedClassFilesInLastRun.++=(classes)
        }

        def invalidatedClassFiles(): Array[File] = {
          invalidatedClassFiles.toArray
        }

        // No need to do any thing, generated classes go to new classes directory
        def generated(classes: Array[File]): Unit = ()

        def complete(success: Boolean): Unit = {
          if (success) {
            // Schedule copying compilation products to visible classes directory
            backgroundTasksWhenNewAnalysis.+=(
              ParallelOps.copyDirectories(
                newClassesDir,
                userClassesDir,
                5,
                compileInputs.scheduler,
                compileInputs.logger
              )
            )
          } else {
            // Delete all compilation products generated in the new classes directory
            backgroundTasksForFailedCompilation.+=(
              tracer.traceTask("delete class files after ") { _ =>
                Task { bloop.io.Paths.delete(AbsolutePath(newClassesDir)); () }
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
        readOnlyClassesDir.toFile -> compileInputs.previousResult
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

      val cancelPromise = compileInputs.cancelPromise
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

    def runAggregateTasks(label: String, tasks: List[Task[Unit]]): CancelableFuture[Unit] = {
      val aggregateTask = Task.gatherUnordered(tasks).map(_ => ())
      tracer
        .traceTask(label)(_ => aggregateTask)
        .runAsync(compileInputs.scheduler)
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
    val logger = compileInputs.logger

    def cancel(): Unit = {
      // Avoid illegal state exception if client cancellation promise is completed
      if (!compileInputs.cancelPromise.isCompleted) {
        compileInputs.cancelPromise.success(())
      }

      // Always report the compilation of a project no matter if it's completed
      reporter.reportCancelledCompilation()
    }

    val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
    val previousSuccessfulProblems =
      previousAnalysis.map(prev => AnalysisUtils.problemsFrom(prev)).getOrElse(Nil)
    val previousProblems: List[ProblemPerPhase] = compileInputs.previousCompilerResult match {
      case f: Compiler.Result.Failed => f.problems
      case c: Compiler.Result.Cancelled => c.problems
      case _: Compiler.Result.Success => previousSuccessfulProblems
      case _ => Nil
    }

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
          val analysis = rebaseAnalysisClassFiles(
            result.analysis().asInstanceOf[Analysis],
            readOnlyClassesDir,
            newClassesDir
          )

          val res = PreviousResult.of(Optional.of(analysis), Optional.of(result.setup()))
          val backgroundTasksExecution = {
            val isNoOp = previousAnalysis.exists(_ == result.analysis())
            if (isNoOp) CancelableFuture.successful(())
            else {
              val aggregateTask =
                Task.gatherUnordered(backgroundTasksWhenNewAnalysis.toList).map(_ => ())
              tracer
                .traceTask("background tasks successful compilation")(_ => aggregateTask)
                .runAsync(compileInputs.scheduler)
            }
          }

          Result.Success(compileInputs.reporter, res, elapsed, backgroundTasksExecution)
        case Failure(_: xsbti.CompileCancelled) =>
          val backgroundTask = runAggregateTasks(
            "background tasks for cancelled compilation",
            backgroundTasksForFailedCompilation.toList
          )

          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Cancelled)
          Result.Cancelled(reporter.allProblemsPerPhase.toList, elapsed, backgroundTask)
        case Failure(cause) =>
          def triggerBackgroundTask = runAggregateTasks(
            "background tasks for cancelled compilation",
            backgroundTasksForFailedCompilation.toList
          )

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
              Result.Failed(failedProblems, None, elapsed, triggerBackgroundTask)
            case t: Throwable =>
              t.printStackTrace()
              Result.Failed(Nil, Some(t), elapsed, triggerBackgroundTask)
          }
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
      analysis: Analysis,
      readOnlyClassesDir: Path,
      newClassesDir: Path
  ): Analysis = {
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
}
