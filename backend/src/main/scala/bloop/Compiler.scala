package bloop

import xsbti.compile._
import xsbti.{Problem, T2}
import java.util.Optional
import java.io.File

import bloop.internal.Ecosystem
import bloop.io.AbsolutePath
import bloop.reporter.Reporter
import sbt.internal.inc.bloop.BloopZincCompiler
import sbt.internal.inc.{FreshCompilerCache, Locate}
import _root_.monix.eval.Task
import bloop.util.CacheHashCode
import sbt.internal.inc.bloop.internal.StopPipelining
import sbt.util.InterfaceUtil

import scala.concurrent.Promise

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
    reporter: Reporter,
    mode: CompileMode,
    dependentResults: Map[File, PreviousResult],
    cancelPromise: Promise[Unit]
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
      reporter: Reporter,
      cancelPromise: Promise[Unit]
  ) extends CompileProgress {
    private var lastPhase: String = ""
    private var lastUnitPath: String = ""
    override def startUnit(phase: String, unitPath: String): Unit = {
      lastPhase = phase
      lastUnitPath = unitPath
    }

    override def advance(current: Int, total: Int): Boolean = {
      val isNotCancelled = !cancelPromise.isCompleted
      if (isNotCancelled) {
        reporter.reportCompilationProgress(current.toLong, total.toLong, lastPhase, lastUnitPath)
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
        reporter: Reporter,
        previous: PreviousResult,
        elapsed: Long
    ) extends Result
        with CacheHashCode

    final case class Failed(
        problems: List[xsbti.Problem],
        t: Option[Throwable],
        elapsed: Long
    ) extends Result
        with CacheHashCode

    final case class Cancelled(
        problems: List[xsbti.Problem],
        elapsed: Long
    ) extends Result
        with CacheHashCode

    object Ok {
      def unapply(result: Result): Option[Result] = result match {
        case s @ (Success(_, _, _) | Empty) => Some(s)
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
          opts.withClassfileManagerType(Optional.of(
            xsbti.compile.TransactionalManagerType.of(classesDirBak, reporter.logger)
          ))
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

    def cancel(): Unit = {
      // Avoid illegal state exception if client cancellation promise is completed
      if (!compileInputs.cancelPromise.isCompleted) {
        compileInputs.cancelPromise.success(())
      }

      // Always report the compilation of a project no matter if it's completed
      reporter.reportCancelledCompilation()
    }

    val previousAnalysis = InterfaceUtil.toOption(compileInputs.previousResult.analysis())
    val previousProblems: List[Problem] = compileInputs.previousCompilerResult match {
      case f: Compiler.Result.Failed => f.problems
      case c: Compiler.Result.Cancelled => c.problems
      case _: Compiler.Result.Success =>
        import scala.collection.JavaConverters._
        val infos =
          previousAnalysis.toList.flatMap(_.readSourceInfos().getAllSourceInfos.asScala.values)
        infos.flatMap(info => info.getReportedProblems.toList).toList
      case _ => Nil
    }

    reporter.reportStartCompilation(previousProblems)
    BloopZincCompiler
      .compile(inputs, compileInputs.mode, reporter)
      .materialize
      .doOnCancel(Task(cancel()))
      .map {
        case Success(result) =>
          // Report end of compilation only after we have reported all warnings from previous runs
          reporter.reportEndCompilation(previousAnalysis, Some(result.analysis), bsp.StatusCode.Ok)
          val res = PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
          Result.Success(compileInputs.reporter, res, elapsed)
        case Failure(_: xsbti.CompileCancelled) =>
          reporter.reportEndCompilation(previousAnalysis, None, bsp.StatusCode.Cancelled)
          Result.Cancelled(reporter.allProblems.toList, elapsed)
        case Failure(cause) =>
          reporter.reportEndCompilation(previousAnalysis, None, bsp.StatusCode.Error)
          cause match {
            case f: StopPipelining => Result.Blocked(f.failedProjectNames)
            case f: xsbti.CompileFailed => Result.Failed(f.problems().toList, None, elapsed)
            case t: Throwable =>
              t.printStackTrace()
              Result.Failed(Nil, Some(t), elapsed)
          }
      }
  }
}
