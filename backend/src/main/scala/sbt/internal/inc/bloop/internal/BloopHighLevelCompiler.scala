// scalafmt: { maxColumn = 250 }
package sbt.internal.inc.bloop.internal

import java.io.File
import java.util.Optional

import monix.eval.Task
import sbt.internal.inc.JavaInterfaceUtil.EnrichOption
import sbt.internal.inc.javac.AnalyzingJavaCompiler
import sbt.internal.inc.{Analysis, AnalyzingCompiler, CompileConfiguration, CompilerArguments, MixedAnalyzingCompiler}
import sbt.util.Logger
import xsbti.AnalysisCallback
import xsbti.compile.{ClassFileManager, CompileOrder, DependencyChanges, IncToolOptions, MultipleOutput, SingleOutput}

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
 * @param logger The logger.
 */
final class BloopHighLevelCompiler(scalac: AnalyzingCompiler, javac: AnalyzingJavaCompiler, config: CompileConfiguration, logger: Logger) {
  private[this] final val setup = config.currentSetup
  private[this] final val classpath = config.classpath.map(_.getAbsoluteFile)

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
      startJavaCompilation: Task[Boolean]
  ): Task[Unit] = {
    def timed[T](label: String, log: Logger)(t: => T): T = {
      val start = System.nanoTime
      val result = t
      val elapsed = System.nanoTime - start
      log.debug(label + " took " + (elapsed / 1e6) + " ms")
      result
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
    logInputs(logger, javaSources.size, scalaSources.size, outputDirs)

    val compileScala: Task[Unit] = Task {
      if (scalaSources.isEmpty) ()
      else {
        val sources = if (setup.order == CompileOrder.Mixed) includedSources else scalaSources
        val cargs = new CompilerArguments(scalac.scalaInstance, config.classpathOptions)
        val args = cargs.apply(Nil, classpath, None, setup.options.scalacOptions).toArray
        timed("Scala compilation", logger) {
          scalac.compileAndSetUpPicklepath(sources.toArray, config.picklepath.toArray, changes, args, setup.output, callback, config.reporter, config.cache, logger, config.progress.toOptional)
        }
      }
    }

    // Note that we only start Java compilation when the task `startJavaCompilation` signals it
    val compileJava: Task[Unit] = startJavaCompilation.map { _ =>
      if (javaSources.isEmpty) ()
      else {
        timed("Java compilation + analysis", logger) {
          val incToolOptions = IncToolOptions.of(
            Optional.of(classfileManager),
            config.incOptions.useCustomizedFileManager()
          )
          val javaOptions = setup.options.javacOptions.toArray[String]
          javac.compile(javaSources, javaOptions, setup.output, callback, incToolOptions, config.reporter, logger, config.progress)
        }
      }
    }

    val compilationTask = {
      /* `Mixed` order defaults to `ScalaThenJava` behaviour.
       * See https://github.com/sbt/zinc/issues/234. */
      if (setup.order == CompileOrder.JavaThenScala) {
        compileJava.flatMap(_ => compileScala)
      } else {
        compileScala.flatMap(_ => compileJava)
      }
    }

    compilationTask.map { _ =>
      // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
      if (javaSources.size + scalaSources.size > 0)
        logger.info("Done compiling.")
    }
  }

  // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
  private[this] def logInputs(
      log: Logger,
      javaCount: Int,
      scalaCount: Int,
      outputDirs: Seq[File]
  ): Unit = {
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaCount)
    val javaMsg = Analysis.counted("Java source", "", "s", javaCount)
    val combined = scalaMsg ++ javaMsg
    if (combined.nonEmpty) {
      val targets = outputDirs.map(_.getAbsolutePath).mkString(",")
      log.info(combined.mkString("Compiling ", " and ", s" to $targets ..."))
    }
  }
}

object BloopHighLevelCompiler {
  def apply(config: CompileConfiguration, log: Logger): BloopHighLevelCompiler = {
    val (searchClasspath, entry) = MixedAnalyzingCompiler.searchClasspathAndLookup(config)
    val scalaCompiler = config.compiler.asInstanceOf[AnalyzingCompiler]
    val javaCompiler = new AnalyzingJavaCompiler(config.javac, config.classpath, config.compiler.scalaInstance, config.classpathOptions, entry, searchClasspath)
    new BloopHighLevelCompiler(scalaCompiler, javaCompiler, config, log)
  }
}
