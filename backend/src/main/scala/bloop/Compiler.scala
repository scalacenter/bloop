package bloop

import xsbti.compile._
import xsbti.T2
import java.util.Optional
import java.io.File
import java.util.concurrent.Executor

import bloop.internal.Ecosystem
import bloop.io.AbsolutePath
import bloop.io.MirrorCompilationIO
import bloop.tracing.BraveTracer
import bloop.logging.{ObservedLogger, Logger}
import bloop.reporter.{ProblemPerPhase, ZincReporter}
import sbt.internal.inc.bloop.BloopZincCompiler
import sbt.internal.inc.{FreshCompilerCache, InitialChanges, Locate}
import bloop.util.{AnalysisUtils, CacheHashCode}
import sbt.internal.inc.bloop.internal.StopPipelining
import sbt.util.InterfaceUtil

import _root_.monix.eval.Task
import _root_.monix.execution.CancelableFuture

import scala.concurrent.Promise

import monix.execution.Scheduler

case class CompileInputs(
    scalaInstance: ScalaInstance,
    compilerCache: CompilerCache,
    sources: Array[AbsolutePath],
    classpath: Array[AbsolutePath],
    store: IRStore,
    classesDir: AbsolutePath,
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
        synchronizeClassFiles: CancelableFuture[Unit]
    ) extends Result
        with CacheHashCode

    final case class Failed(
        problems: List[ProblemPerPhase],
        t: Option[Throwable],
        elapsed: Long
    ) extends Result
        with CacheHashCode

    final case class Cancelled(
        problems: List[ProblemPerPhase],
        elapsed: Long
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
        case f @ (Failed(_, _, _) | Cancelled(_, _) | Blocked(_) | GlobalError(_)) => Some(f)
        case _ => None
      }
    }
  }

  def compile(compileInputs: CompileInputs): Task[Result] = {
    // Copy the classes directory for the project to compile if it doesn't exist yet
    java.nio.file.Files.createDirectories(compileInputs.classesDir.underlying)
    val classesDir = compileInputs.classesDir.toFile
    val classesDirBak = compileInputs.classesDir.getParent.resolve("classes.bak").toFile
    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getCompilationOptions(inputs: CompileInputs): CompileOptions = {
      val sources = inputs.sources // Sources are all files
      val classesDir = inputs.classesDir.toFile
      val classpath = inputs.classpath.map(_.toFile)

      CompileOptions
        .create()
        .withClassesDirectory(classesDir)
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
      val results = compileInputs.dependentResults.+(classesDir -> compileInputs.previousResult)
      val lookup = new ZincClasspathEntryLookup(results)
      val reporter = compileInputs.reporter
      val compilerCache = new FreshCompilerCache
      val cacheFile = compileInputs.baseDirectory.resolve("cache").toFile
      val incOptions = {
        def withTransactional(opts: IncOptions): IncOptions = {
          opts.withClassfileManagerType(
            Optional.of(
              xsbti.compile.TransactionalManagerType.of(classesDirBak, compileInputs.logger)
            )
          )
        }

        val disableIncremental = java.lang.Boolean.getBoolean("bloop.zinc.disabled")
        val opts = withTransactional(IncOptions.create().withEnabled(!disableIncremental))
        if (!compileInputs.scalaInstance.isDotty) opts
        else Ecosystem.supportDotty(opts)
      }
      val progress =
        Optional.of[CompileProgress](new BloopProgress(reporter, compileInputs.cancelPromise))
      val setup =
        Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
      // We only set the pickle promise here, but the java signal is set in `BloopHighLevelCompiler`
      compileInputs.mode match {
        case p: CompileMode.Pipelined => setup.withIrPromise(p.irs)
        case pp: CompileMode.ParallelAndPipelined => setup.withIrPromise(pp.irs)
        case _ => setup
      }
    }

    val start = System.nanoTime()
    val scalaInstance = compileInputs.scalaInstance
    val classpathOptions = compileInputs.classpathOptions
    val compilers = compileInputs.compilerCache.get(scalaInstance)
    val inputs = getInputs(compilers)

    // We don't need nanosecond granularity, we're happy with milliseconds
    def elapsed: Long = ((System.nanoTime() - start).toDouble / 1e6).toLong

    import ch.epfl.scala.bsp
    import scala.util.{Success, Failure}
    val reporter = compileInputs.reporter
    val logger = compileInputs.logger

    import java.util.UUID
    import java.nio.file.Files
    val newClassesDir = Files.createDirectories(
      compileInputs.classesDir.getParent.underlying.resolve(s"classes.new-${UUID.randomUUID}")
    )

    val (copyTask, stopFileWatcher) = MirrorCompilationIO(
      classesDir.toPath.toRealPath(),
      newClassesDir.toRealPath(),
      compileInputs.scheduler,
      compileInputs.executor,
      logger
    )

    def cancel(): Unit = {
      // Avoid illegal state exception if client cancellation promise is completed
      if (!compileInputs.cancelPromise.isCompleted) {
        compileInputs.cancelPromise.success(())
      }

      /*
      // Handle the copying task in the background
      stopFileWatcher.cancel()
       */

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

    reporter.reportStartCompilation(previousProblems)
    BloopZincCompiler
      .compile(inputs, compileInputs.mode, reporter, logger, compileInputs.tracer)
      .materialize
      .doOnCancel(Task(cancel()))
      // Cancel the file watcher when compilation is done, no matter what its result is
      //.map(result => { stopFileWatcher.cancel(); result })
      .map {
        case Success(result) =>
          // Report end of compilation only after we have reported all warnings from previous runs
          val isNoOp = previousAnalysis.exists(_ == result.analysis())
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Ok)
          val res = PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
          val copyHandle = {
            if (isNoOp) CancelableFuture.successful(())
            else {
              compileInputs.tracer
                .traceTask("copy class files")(_ => copyTask)
                .runAsync(compileInputs.scheduler)
            }
          }

          Result.Success(compileInputs.reporter, res, elapsed, copyHandle)
        case Failure(_: xsbti.CompileCancelled) =>
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Cancelled)
          Result.Cancelled(reporter.allProblemsPerPhase.toList, elapsed)
        case Failure(cause) =>
          reporter.reportEndCompilation(previousSuccessfulProblems, bsp.StatusCode.Error)
          cause match {
            case f: StopPipelining => Result.Blocked(f.failedProjectNames)
            case f: xsbti.CompileFailed =>
              // We cannot assert reporter.problems == f.problems, so we aggregate them together
              val reportedProblems = reporter.allProblemsPerPhase.toList
              val rawProblemsFromReporter = reportedProblems.iterator.map(_.problem).toSet
              val newProblems = f
                .problems()
                .flatMap { p =>
                  if (rawProblemsFromReporter.contains(p)) Nil
                  else List(ProblemPerPhase(p, None))
                }
                .toList
              val failedProblems = reportedProblems ++ newProblems
              Result.Failed(failedProblems, None, elapsed)
            case t: Throwable =>
              t.printStackTrace()
              Result.Failed(Nil, Some(t), elapsed)
          }
      }
  }
}
