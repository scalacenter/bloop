package bloop.engine.tasks

import bloop.config.Config
import bloop.bsp.BloopCompileSnippetResult

import monix.eval.Task
import java.nio.file.Files
import bloop.CompileInputs
import bloop.ScalaInstance
import bloop.logging.BspServerLogger
import bloop.io.AbsolutePath
import bloop.engine.State
import sbt.internal.inc.FreshCompilerCache
import bloop.UniqueCompileInputs
import bloop.CompileOutPaths
import xsbti.compile.ClasspathOptionsUtil
import bloop.engine.caches.LastSuccessfulResult
import bloop.reporter.BspProjectReporter
import xsbti.compile.ClasspathOptions
import xsbti.compile.CompileOrder
import bloop.reporter.BspProjectReporter
import bloop.logging.ObservedLogger
import bloop.reporter.ReporterAction
import bloop.logging.LoggerAction
import monix.reactive.Observable
import monix.reactive.MulticastStrategy
import bloop.CompileMode
import bloop.engine.tasks.compilation.SimpleOracle
import scala.concurrent.Promise
import bloop.tracing.BraveTracer
import bloop.engine.ExecutionContext
import ch.epfl.scala.bsp
import bloop.bsp.ProjectUris
import bloop.reporter.ReporterConfig
import bloop.reporter.LogReporter
import bloop.logging.Logger

object CompileSnippetTask {
  val cachedCompiler = new sbt.internal.inc.CompilerCache(10)
  def compileSnippet(
      contents: Array[Byte],
      config: Config.SnippetConfig,
      buildState: State,
      logger: Logger //BspServerLogger
  ): Task[bloop.Compiler.Result] = {
    val buildDir = AbsolutePath(Files.createTempDirectory("compile-snippet-sources"))
    val sourcesDir = Files.createDirectories(buildDir.resolve("src").underlying)
    val snippetId = scala.util.Random.nextInt().toString
    val uniqueSourceFile = AbsolutePath(sourcesDir.resolve(s"snippet-$snippetId"))
    Files.write(uniqueSourceFile.underlying, contents)

    val scalacOptions = config.scala.options.toArray
    val classpath = config.classpath.map(AbsolutePath(_)).toArray
    val ec = bloop.engine.ExecutionContext.ioScheduler
    val instance = {
      if (config.scala.jars.isEmpty) None
      else {
        import config.scala.{organization, name, version}
        val scalaJars = config.scala.jars.map(AbsolutePath.apply)
        Some(ScalaInstance(organization, name, version, scalaJars, logger)(ec))
      }
    }

    val bspUri = bsp.Uri(ProjectUris.toURI(buildDir, snippetId))
    val reporterConfig = ReporterConfig.defaultFormat.copy(reverseOrder = false)
    /*
    val reporter = new BspProjectReporter(
      snippetId,
      bspUri,
      logger,
      buildDir,
      reporterConfig,
      false
    )
     */
    val reporter = new LogReporter(snippetId, logger, buildDir, reporterConfig)

    val (observer, obs) = {
      Observable.multicast[Either[ReporterAction, LoggerAction]](MulticastStrategy.replay)(ec)
    }

    val targetDir = Files.createDirectories(buildDir.resolve("target").underlying)
    val genericClassesDir = AbsolutePath(Files.createDirectories(targetDir.resolve("classes")))
    val internalReadOnlyDir = CompileOutPaths.deriveEmptyClassesDir(snippetId, genericClassesDir)
    val externalClassesDir = AbsolutePath(
      Files.createDirectories(targetDir.resolve("external-classes"))
    )

    // asdfa
    instance match {
      case None => sys.error(s"No scala instance, scala config was ${config.scala}")
      case Some(instance) =>
        val out = CompileOutPaths(None, genericClassesDir, externalClassesDir, internalReadOnlyDir)
        bloop.Compiler.compile(
          CompileInputs(
            instance,
            buildState.compilerCache,
            cachedCompiler,
            Array(uniqueSourceFile),
            classpath,
            UniqueCompileInputs.emptyFor(snippetId),
            out,
            buildDir,
            scalacOptions,
            Array(),
            CompileOrder.Mixed,
            ClasspathOptionsUtil.auto(),
            LastSuccessfulResult.EmptyPreviousResult,
            bloop.Compiler.Result.Empty,
            reporter,
            ObservedLogger(logger, observer),
            CompileMode.Sequential(new SimpleOracle),
            Map.empty,
            Promise(),
            BraveTracer.apply("hello"),
            ec,
            ExecutionContext.ioExecutor,
            Set.empty,
            Map.empty
          )
        )
    }
  }
}
