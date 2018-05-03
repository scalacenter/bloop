package bloop

import xsbti.compile._
import xsbti.T2
import java.util.Optional
import java.io.File
import java.net.URI
import java.util.concurrent.CompletableFuture

import bloop.internal.Ecosystem
import bloop.io.{AbsolutePath, Paths}
import bloop.logging.Logger
import bloop.reporter.Reporter
import sbt.internal.inc.javac.JavaTools
import sbt.internal.inc.{FreshCompilerCache, Locate, ZincUtil}
import sbt.util.InterfaceUtil

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
    pickleReceiver: Option[CompletableFuture[URI]],
    javaClasspath: Option[CompletableFuture[Seq[File]]],
    logger: Logger
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
    final case object Empty extends Result
    final case class Blocked(on: List[String]) extends Result
    final case class Cancelled(elapsed: Long) extends Result
    final case class Failed(problems: Array[xsbti.Problem], elapsed: Long) extends Result
    final case class Success(reporter: Reporter, previous: PreviousResult, elapsed: Long)
        extends Result

    object NotOk {
      def unapply(result: Result): Option[Result] = result match {
        case f @ (Failed(_, _) | Cancelled(_) | Blocked(_)) => Some(f)
        case _ => None
      }
    }
  }

  def compile(compileInputs: CompileInputs): Result = {
    def getInputs(compilers: Compilers): Inputs = {
      val options = getCompilationOptions(compileInputs)
      val setup = getSetup(compileInputs)
      Inputs.of(compilers, options, setup, compileInputs.previousResult)
    }

    def getCompilationOptions(inputs: CompileInputs): CompileOptions = {
      val uniqueSources = inputs.sources.distinct
      // Get all the source files in the directories that may be present in `sources`
      val sources = uniqueSources.flatMap(src => Paths.getAllFiles(src, "glob:**.{scala,java}")).distinct
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
        if (!compileInputs.scalaInstance.isDotty) IncOptions.create()
        else Ecosystem.supportDotty(IncOptions.create())
      }
      val progress = Optional.empty[CompileProgress]
      val receiver = compileInputs.pickleReceiver
      val setup =
        Setup.create(lookup, skip, cacheFile, compilerCache, incOptions, reporter, progress, empty)
      if (!receiver.isDefined) setup
      else setup.withPicklePromise(InterfaceUtil.toOptional(receiver))
    }

    /**
     * Modifies the java tools to use our own javac compiler in case pipelined
     * compilation is enabled. Our javac compiler is just a valid `JavaCompiler`
     * definition that wraps the underlying java compilation but doesn't start
     * compiling the Java project until the ready promise is completed.
     *
     * The ready promise is completed by the user of this API upon the completion
     * of all the Scala compilation processes of dependent projects.
     */
    def setUpCompilers(compilers: Compilers): Compilers = {
      compileInputs.javaClasspath match {
        case Some(startFuture) =>
          val oldJavaTools = compilers.javaTools()
          val newJavac = new BlockingJavaCompiler(startFuture, oldJavaTools.javac())
          compilers.withJavaTools(JavaTools(newJavac, oldJavaTools.javadoc()))
        case None => compilers
      }
    }

    val start = System.nanoTime()
    val scalaInstance = compileInputs.scalaInstance
    val classpathOptions = compileInputs.classpathOptions
    val compilers = setUpCompilers(compileInputs.compilerCache.get(scalaInstance))
    val inputs = getInputs(compilers)
    val incrementalCompiler = ZincUtil.defaultIncrementalCompiler

    // We don't want nanosecond granularity, we're happy with milliseconds
    def elapsed: Long = ((System.nanoTime() - start).toDouble / 1e6).toLong

    try {
      val result0 = incrementalCompiler.compile(inputs, compileInputs.logger)
      val result = PreviousResult.of(Optional.of(result0.analysis()), Optional.of(result0.setup()))
      Result.Success(compileInputs.reporter, result, elapsed)
    } catch {
      case f: xsbti.CompileFailed => Result.Failed(f.problems(), elapsed)
      case _: xsbti.CompileCancelled => Result.Cancelled(elapsed)
    }
  }
}
