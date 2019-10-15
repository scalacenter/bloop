// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.io.File
import java.util.Optional
import java.util.concurrent.CompletableFuture

import bloop.reporter.ZincReporter
import bloop.logging.ObservedLogger
import bloop.{CompileMode, JavaSignal}
import bloop.tracing.BraveTracer

import monix.eval.Task
import sbt.internal.inc.JavaInterfaceUtil.EnrichOption
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import sbt.internal.inc.{AnalyzingCompiler, CompileConfiguration, CompilerArguments, MixedAnalyzingCompiler, ScalaInstance, Stamper, Stamps}
import sbt.util.Logger
import xsbti.{AnalysisCallback, CompileFailed}
import xsbti.compile._

import scala.util.control.NonFatal
import sbt.internal.inc.JarUtils
import scala.concurrent.Promise
import xsbt.InterfaceCompileCancelled

/**
 *
 * Defines a high-level compiler after [[sbt.internal.inc.MixedAnalyzingCompiler]], with the
 * exception that this one changes the interface to allow compilation to be task-based and
 * only proceed after external tasks signal it (see `startJavaCompilation` in `compile`).
 *
 * This change is paramount to get pipelined incremental compilation working.
 *
 * @param scalac The Scala compiler (this one takes the concrete implementation, not an interface).
 * @param javac The concrete Java compiler.
 * @param config The compilation configuration.
 * @param reporter The reporter to be used to compile.
 */
final class BloopHighLevelCompiler(
    scalac: AnalyzingCompiler,
    javac: AnalyzingJavaCompiler,
    config: CompileConfiguration,
    reporter: ZincReporter,
    logger: ObservedLogger[_],
    tracer: BraveTracer
) {
  private[this] final val setup = config.currentSetup
  private[this] final val classpath = config.classpath.map(_.getAbsoluteFile)

  private[this] val JavaCompleted: Promise[Unit] = Promise.successful(())

  /**
   * Compile
   *
   * @param sourcesToCompile The source to (incrementally) compile at one time.
   * @param changes The dependency changes detected previously.
   * @param callback The analysis callback from the compiler.
   * @param classfileManager The classfile manager charged with the class files lifecycle.
   * @param startJavaCompilation A task that will end whenever Java compilation can start.
   * @return
   */
  def compile(
      sourcesToCompile: Set[File],
      changes: DependencyChanges,
      callback: AnalysisCallback,
      classfileManager: ClassFileManager,
      compileMode: CompileMode,
      cancelPromise: Promise[Unit]
  ): Task[Unit] = {
    def timed[T](label: String)(t: => T): T = {
      tracer.trace(label) { _ =>
        t
      }
    }

    val outputDirs = {
      setup.output match {
        case single: SingleOutput => List(single.getOutputDirectory)
        case mult: MultipleOutput => mult.getOutputGroups.iterator.map(_.getOutputDirectory).toList
      }
    }

    outputDirs.foreach { d =>
      if (!d.getPath.endsWith(".jar") && !d.exists())
        sbt.io.IO.createDirectory(d)
    }

    val includedSources = config.sources.filter(sourcesToCompile)
    val (javaSources, scalaSources) = includedSources.partition(_.getName.endsWith(".java"))
    val existsCompilation = javaSources.size + scalaSources.size > 0
    if (existsCompilation) {
      reporter.reportStartIncrementalCycle(includedSources, outputDirs)
    }

    // Note `pickleURI` has already been used to create the analysis callback in `BloopZincCompiler`
    val (pipeline: Boolean, batches: Option[Int], completeJava: Promise[Unit], fireJavaCompilation: Task[JavaSignal], separateJavaAndScala: Boolean) = {
      compileMode match {
        case _: CompileMode.Sequential => (false, None, JavaCompleted, Task.now(JavaSignal.ContinueCompilation), false)
        case CompileMode.Pipelined(completeJava, _, fireJavaCompilation, _, separateJavaAndScala) =>
          (true, None, completeJava, fireJavaCompilation, separateJavaAndScala)
      }
    }

    // Complete empty java promise if there are no java sources
    if (javaSources.isEmpty && !completeJava.isCompleted)
      completeJava.trySuccess(())

    val compileScala: Task[Unit] = {
      if (scalaSources.isEmpty) Task.now(())
      else {
        val sources = {
          if (separateJavaAndScala) {
            // No matter if it's scala->java or mixed, we populate java symbols from sources
            val transitiveJavaSources = compileMode.oracle.askForJavaSourcesOfIncompleteCompilations
            includedSources ++ transitiveJavaSources.filterNot(_.getName == "routes.java")
          } else {
            if (setup.order == CompileOrder.Mixed) includedSources
            else scalaSources
          }
        }
        val cargs = new CompilerArguments(scalac.scalaInstance, config.classpathOptions)
        def compileSources(
            sources: Seq[File],
            scalacOptions: Array[String],
            callback: AnalysisCallback
        ): Unit = {
          try {
            val args = cargs.apply(Nil, classpath, None, scalacOptions).toArray
            scalac.compile(sources.toArray, changes, args, setup.output, callback, config.reporter, config.cache, logger, config.progress.toOptional)
          } catch {
            case NonFatal(t) =>
              // If scala compilation happens, complete the java promise so that it doesn't block
              completeJava.tryFailure(t)

              t match {
                case _: NullPointerException if cancelPromise.isCompleted =>
                  throw new InterfaceCompileCancelled(Array(), "Caught NPE when compilation was cancelled!")
                case t => throw t
              }
          }
        }

        def compileSequentially: Task[Unit] = Task {
          val scalacOptions = setup.options.scalacOptions
          val args = cargs.apply(Nil, classpath, None, scalacOptions).toArray
          timed("scalac") {
            compileSources(sources, scalacOptions, callback)
          }
        }

        batches match {
          case Some(batches) => sys.error("Parallel compilation is not yet supported!")
          case None => compileSequentially
        }
      }
    }

    val compileJava: Task[Unit] = Task {
      timed("javac") {
        val incToolOptions = IncToolOptions.of(
          Optional.of(classfileManager),
          config.incOptions.useCustomizedFileManager()
        )
        val javaOptions = setup.options.javacOptions.toArray[String]
        try {
          javac.compile(javaSources, javaOptions, setup.output, callback, incToolOptions, config.reporter, logger, config.progress)
          completeJava.trySuccess(())
          ()
        } catch {
          case f: CompileFailed =>
            // Intercept and report manually because https://github.com/sbt/zinc/issues/520
            config.reporter.printSummary()
            completeJava.tryFailure(f)
            throw f
        }
      }
    }

    val combinedTasks = {
      if (separateJavaAndScala) {
        val compileJavaSynchronized = {
          fireJavaCompilation.flatMap {
            case JavaSignal.ContinueCompilation => compileJava
            case JavaSignal.FailFastCompilation(failedProjects) =>
              throw new StopPipelining(failedProjects)
          }
        }

        if (javaSources.isEmpty) compileScala
        else {
          if (setup.order == CompileOrder.JavaThenScala) {
            Task.gatherUnordered(List(compileJavaSynchronized, compileScala)).map(_ => ())
          } else {
            compileScala.flatMap(_ => compileJavaSynchronized)
          }
        }
      } else {
        // Note that separate java and scala is not enabled under pipelining
        fireJavaCompilation.flatMap {
          case JavaSignal.ContinueCompilation =>
            if (setup.order == CompileOrder.JavaThenScala) {
              compileJava.flatMap(_ => compileScala)
            } else {
              compileScala.flatMap(_ => compileJava)
            }

          case JavaSignal.FailFastCompilation(failedProjects) =>
            throw new StopPipelining(failedProjects)
        }
      }
    }

    Task(System.nanoTime).flatMap { nanoStart =>
      combinedTasks.materialize.map { r =>
        if (existsCompilation) {
          val elapsedMs = (System.nanoTime - nanoStart) / 1000000
          reporter.reportEndIncrementalCycle(elapsedMs, r)
        }

        r
      }
    }.dematerialize
  }
}

object BloopHighLevelCompiler {
  def apply(config: CompileConfiguration, reporter: ZincReporter, logger: ObservedLogger[_], tracer: BraveTracer): BloopHighLevelCompiler = {
    val (searchClasspath, entry) = MixedAnalyzingCompiler.searchClasspathAndLookup(config)
    val scalaCompiler = config.compiler.asInstanceOf[AnalyzingCompiler]
    val javaCompiler = new AnalyzingJavaCompiler(config.javac, config.classpath, config.compiler.scalaInstance, config.classpathOptions, entry, searchClasspath)
    new BloopHighLevelCompiler(scalaCompiler, javaCompiler, config, reporter, logger, tracer)
  }
}
