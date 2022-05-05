// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional

import scala.concurrent.Promise
import scala.util.control.NonFatal

import bloop.logging.ObservedLogger
import bloop.reporter.ZincReporter
import bloop.tracing.BraveTracer

import monix.eval.Task
import sbt.internal.inc.AnalyzingCompiler
import sbt.internal.inc.CompileConfiguration
import sbt.internal.inc.CompilerArguments
import sbt.internal.inc.JavaInterfaceUtil.EnrichOption
import sbt.internal.inc.MixedAnalyzingCompiler
import sbt.internal.inc.PlainVirtualFileConverter
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import xsbt.InterfaceCompileCancelled
import xsbti.AnalysisCallback
import xsbti.CompileFailed
import xsbti.VirtualFile
import xsbti.compile._

/**
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
  private[this] final val classpath: Seq[VirtualFile] = config.classpath
  private[this] final val classpathNio: Seq[Path] = classpath.map(PlainVirtualFileConverter.converter.toPath)

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
      sourcesToCompile: Set[VirtualFile],
      changes: DependencyChanges,
      callback: AnalysisCallback,
      classfileManager: ClassFileManager,
      cancelPromise: Promise[Unit],
      classpathOptions: ClasspathOptions
  ): Task[Unit] = {
    def timed[T](label: String)(t: => T): T = {
      tracer.trace(label) { _ =>
        t
      }
    }

    val outputDirs = {
      setup.output match {
        case single: SingleOutput => List(single.getOutputDirectoryAsPath())
        case mult: MultipleOutput => mult.getOutputGroups.iterator.map(_.getOutputDirectoryAsPath()).toList
      }
    }

    outputDirs.foreach { d =>
      if (!d.endsWith(".jar") && !Files.exists(d))
        sbt.io.IO.createDirectory(d.toFile())
    }

    val includedSources = config.sources.filter(sourcesToCompile)
    val (javaSources, scalaSources) = includedSources.partition(_.name().endsWith(".java"))
    val existsCompilation = javaSources.size + scalaSources.size > 0
    if (existsCompilation) {
      reporter.reportStartIncrementalCycle(includedSources, outputDirs.map(_.toFile()))
    }

    // Complete empty java promise if there are no java sources
    if (javaSources.isEmpty && !JavaCompleted.isCompleted)
      JavaCompleted.trySuccess(())

    val compileScala: Task[Unit] = {
      if (scalaSources.isEmpty) Task.now(())
      else {
        val sources = {
          if (setup.order == CompileOrder.Mixed) {
            // No matter if it's scala->java or mixed, we populate java symbols from sources
            includedSources
          } else {
            scalaSources
          }
        }

        def compilerArgs: CompilerArguments = {
          import sbt.internal.inc.CompileFailed
          if (scalac.scalaInstance.compilerJars().isEmpty) {
            throw new CompileFailed(new Array(0), s"Expected Scala compiler jar in Scala instance containing ${scalac.scalaInstance.allJars().mkString(", ")}", new Array(0))
          }

          if (scalac.scalaInstance.libraryJars().isEmpty) {
            throw new CompileFailed(new Array(0), s"Expected Scala library jar in Scala instance containing ${scalac.scalaInstance.allJars().mkString(", ")}", new Array(0))
          }

          new CompilerArguments(scalac.scalaInstance, classpathOptions)
        }

        def compileSources(
            sources: Seq[VirtualFile],
            scalacOptions: Array[String],
            callback: AnalysisCallback
        ): Unit = {
          try {
            val args = compilerArgs.makeArguments(Nil, classpathNio, scalacOptions)
            scalac.compile(
              sources.toArray,
              classpath.toArray,
              PlainVirtualFileConverter.converter,
              changes,
              args.toArray,
              setup.output,
              callback,
              config.reporter,
              config.progress.toOptional,
              logger
            )
          } catch {
            case NonFatal(t) =>
              // If scala compilation happens, complete the java promise so that it doesn't block
              JavaCompleted.tryFailure(t)

              t match {
                case _: NullPointerException if cancelPromise.isCompleted =>
                  throw new InterfaceCompileCancelled(Array(), "Caught NPE when compilation was cancelled!")
                case t => throw t
              }
          }
        }

        def compileSequentially: Task[Unit] = Task {
          val scalacOptions = setup.options.scalacOptions
          val args = compilerArgs.makeArguments(Nil, classpathNio, scalacOptions)
          timed("scalac") {
            compileSources(sources, scalacOptions, callback)
          }
        }

        compileSequentially
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
          javac.compile(javaSources, Nil, PlainVirtualFileConverter.converter, javaOptions, setup.output, None, callback, incToolOptions, config.reporter, logger, config.progress)
          JavaCompleted.trySuccess(())
          ()
        } catch {
          case f: CompileFailed =>
            // Intercept and report manually because https://github.com/sbt/zinc/issues/520
            config.reporter.printSummary()
            JavaCompleted.tryFailure(f)
            throw f
        }
      }
    }

    val combinedTasks =
      if (setup.order == CompileOrder.JavaThenScala) {
        compileJava.flatMap(_ => compileScala)
      } else {
        compileScala.flatMap(_ => compileJava)
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
  def apply(config: CompileConfiguration, reporter: ZincReporter, logger: ObservedLogger[_], tracer: BraveTracer, classpathOptions: ClasspathOptions): BloopHighLevelCompiler = {
    val (searchClasspath, entry) = MixedAnalyzingCompiler.searchClasspathAndLookup(config)
    val scalaCompiler = config.compiler.asInstanceOf[AnalyzingCompiler]
    val javaCompiler = new AnalyzingJavaCompiler(config.javac, config.classpath, config.compiler.scalaInstance, classpathOptions, entry, searchClasspath)
    new BloopHighLevelCompiler(scalaCompiler, javaCompiler, config, reporter, logger, tracer)
  }
}
