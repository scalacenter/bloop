package bloop

import xsbti.compile._
import xsbti.T2
import java.util.Optional
import java.io.File
import java.net.URI

import bloop.internal.Ecosystem
import bloop.io.AbsolutePath
import bloop.reporter.Reporter
import sbt.internal.inc.bloop.{BloopZincCompiler, CompileMode}
import sbt.internal.inc.{FreshCompilerCache, Locate}
import _root_.monix.eval.Task
import bloop.util.CacheHashCode
import sbt.internal.inc.bloop.internal.StopPipelining

case class CompileInputs(
    scalaInstance: ScalaInstance,
    compilerCache: CompilerCache,
    sources: Array[AbsolutePath],
    classpath: Array[AbsolutePath],
    picklepath: Array[URI],
    classesDir: AbsolutePath,
    baseDirectory: AbsolutePath,
    scalacOptions: Array[String],
    javacOptions: Array[String],
    compileOrder: CompileOrder,
    classpathOptions: ClasspathOptions,
    previousResult: PreviousResult,
    reporter: Reporter,
    mode: CompileMode
)

object Compiler {
  private final class ZincClasspathEntryLookup(previousResult: PreviousResult)
      extends PerClasspathEntryLookup {
    override def analysis(classpathEntry: File): Optional[CompileAnalysis] =
      previousResult.analysis
    override def definesClass(classpathEntry: File): DefinesClass =
      Locate.definesClass(classpathEntry)
  }

  sealed trait Result
  object Result {
    final case object Empty extends Result with CacheHashCode
    final case class Blocked(on: List[String]) extends Result with CacheHashCode
    final case class Cancelled(elapsed: Long) extends Result with CacheHashCode
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

    object Ok {
      def unapply(result: Result): Option[Result] = result match {
        case s @ (Success(_, _, _) | Empty) => Some(s)
        case _ => None
      }
    }

    object NotOk {
      def unapply(result: Result): Option[Result] = result match {
        case f @ (Failed(_, _, _) | Cancelled(_) | Blocked(_)) => Some(f)
        case _ => None
      }
    }
  }

  def compile(compileInputs: CompileInputs): Task[Result] = {
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
        .withPicklepath(inputs.picklepath)
        .withScalacOptions(inputs.scalacOptions)
        .withJavacOptions(inputs.javacOptions)
        .withClasspathOptions(inputs.classpathOptions)
        .withOrder(inputs.compileOrder)
    }

    def getSetup(compileInputs: CompileInputs): Setup = {
      val skip = false
      val empty = Array.empty[T2[String, String]]
      val lookup = new ZincClasspathEntryLookup(compileInputs.previousResult)
      val reporter = compileInputs.reporter
      val compilerCache = new FreshCompilerCache
      val cacheFile = compileInputs.baseDirectory.resolve("cache").toFile
      val incOptions = {
        val disableIncremental = java.lang.Boolean.getBoolean("bloop.zinc.disabled")
        val opts = IncOptions.create().withEnabled(!disableIncremental)
        if (!compileInputs.scalaInstance.isDotty) opts
        else Ecosystem.supportDotty(opts)
      }
      val progress = Optional.empty[CompileProgress]
      val setup =
        Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
      // We only set the pickle promise here, but the java signal is set in `BloopHighLevelCompiler`
      compileInputs.mode match {
        case CompileMode.Pipelined(pickleUri, _, _) =>
          setup.withPicklePromise(pickleUri)
        case CompileMode.ParallelAndPipelined(_, pickleUri, _, _) =>
          setup.withPicklePromise(pickleUri)
        case _ => setup
      }
    }

    val start = System.nanoTime()
    val scalaInstance = compileInputs.scalaInstance
    val classpathOptions = compileInputs.classpathOptions
    val compilers = compileInputs.compilerCache.get(scalaInstance)
    val inputs = getInputs(compilers)

    // We don't want nanosecond granularity, we're happy with milliseconds
    def elapsed: Long = ((System.nanoTime() - start).toDouble / 1e6).toLong

    import scala.util.{Success, Failure}
    val logger = compileInputs.reporter.logger
    BloopZincCompiler.compile(inputs, compileInputs.mode, logger).materialize.map {
      case Success(result) =>
        val res = PreviousResult.of(Optional.of(result.analysis()), Optional.of(result.setup()))
        Result.Success(compileInputs.reporter, res, elapsed)
      case Failure(f: StopPipelining) => Result.Blocked(f.failedProjectNames)
      case Failure(f: xsbti.CompileFailed) => Result.Failed(f.problems().toList, None, elapsed)
      case Failure(_: xsbti.CompileCancelled) => Result.Cancelled(elapsed)
      case Failure(t: Throwable) =>
        t.printStackTrace()
        Result.Failed(Nil, Some(t), elapsed)
    }
  }
}
