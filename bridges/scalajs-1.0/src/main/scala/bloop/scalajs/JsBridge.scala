package bloop.scalajs

import java.nio.file.Path

import bloop.config.Config.{JsConfig, LinkerMode, ModuleKindJS}
import bloop.data.Project
import bloop.logging.{DebugFilter, Logger => BloopLogger}
import bloop.scalajs.jsenv.{JsDomNodeJsEnv, NodeJSConfig, NodeJSEnv}
import org.scalajs.logging.{Level, Logger => JsLogger}
import org.scalajs.linker.{PathIRContainer, PathOutputFile, StandardImpl}
import org.scalajs.linker.interface.{ModuleKind => ScalaJSModuleKind, _}
import org.scalajs.jsenv.Input
import org.scalajs.testing.adapter.{TestAdapter, TestAdapterInitializer}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

/**
 * Defines operations provided by the Scala.JS 1.x toolchain.
 *
 * The 1.x js bridge needs to inline the implementation of `NodeJSEnv`,
 * `JSDOMNodeJSEnv` and `ComRunner` because there is a bug in the latest
 * Scala.js release that does not run `close` on the underlying process,
 * skipping the destruction of the process running Scala.js tests. Aside
 * from leaking, this is fatal in Windows because the underlying process
 * is alive and keeps open references to the output JS file.
 *
 * We can remove all of the js environments and runners as soon as this
 * issue is fixed upstream. Note that our 0.6.x version handles cancellation
 * correctly.
 */
object JsBridge {
  private class Logger(logger: BloopLogger)(implicit filter: DebugFilter) extends JsLogger {
    override def log(level: Level, message: => String): Unit =
      level match {
        case Level.Error => logger.error(message)
        case Level.Warn => logger.warn(message)
        case Level.Info => logger.info(message)
        case Level.Debug => logger.debug(message)(filter)
      }
    override def trace(t: => Throwable): Unit = logger.trace(t)
  }

  def link(
      config: JsConfig,
      project: Project,
      classpath: Array[Path],
      runMain: java.lang.Boolean,
      mainClass: Option[String],
      target: Path,
      logger: BloopLogger,
      executionContext: ExecutionContext
  ): Unit = {
    implicit val ec = executionContext
    implicit val logFilter: DebugFilter = DebugFilter.Link
    val enableOptimizer = config.mode == LinkerMode.Release
    val semantics = config.mode match {
      case LinkerMode.Debug => Semantics.Defaults
      case LinkerMode.Release => Semantics.Defaults.optimized
    }

    val moduleKind = config.kind match {
      case ModuleKindJS.NoModule => ScalaJSModuleKind.NoModule
      case ModuleKindJS.CommonJSModule => ScalaJSModuleKind.CommonJSModule
    }

    val cache = StandardImpl.irFileCache().newCache
    val irContainersPairs = PathIRContainer.fromClasspath(classpath)
    val libraryIrsFuture = irContainersPairs.flatMap(pair => cache.cached(pair._1))

    val moduleInitializers = mainClass match {
      case Some(mainClass) if runMain =>
        logger.debug(s"Setting up main module initializers for ${project}")
        List(ModuleInitializer.mainMethodWithArgs(mainClass, "main"))
      case _ =>
        if (runMain) {
          logger.debug(s"Setting up no module initializers, commonjs module detected ${project}")
          Nil // If run is disabled, it's a commonjs module and we link with exports
        } else {
          // There is no main class, install the test module initializers
          logger.debug(s"Setting up test module initializers for ${project}")
          List(
            ModuleInitializer.mainMethod(
              TestAdapterInitializer.ModuleClassName,
              TestAdapterInitializer.MainMethodName
            )
          )
        }
    }

    val output = LinkerOutput(PathOutputFile(target))
    val jsConfig = StandardConfig()
      .withOptimizer(enableOptimizer)
      .withClosureCompilerIfAvailable(enableOptimizer)
      .withSemantics(semantics)
      .withModuleKind(moduleKind)
      .withSourceMap(config.emitSourceMaps)

    val resultFuture = for {
      libraryIRs <- libraryIrsFuture
      _ <- StandardImpl
        .linker(jsConfig)
        .link(libraryIRs, moduleInitializers, output, new Logger(logger))
    } yield ()

    Await.result(resultFuture, Duration.Inf)
  }

  /** @return (list of frameworks, function to close test adapter) */
  def discoverTestFrameworks(
      frameworkNames: List[List[String]],
      nodePath: String,
      jsPath: Path,
      baseDirectory: Path,
      logger: BloopLogger,
      jsConfig: JsConfig,
      env: Map[String, String]
  ): (List[sbt.testing.Framework], () => Unit) = {
    implicit val debugFilter: DebugFilter = DebugFilter.Test
    val nodeModules = baseDirectory.resolve("node_modules").toString
    logger.debug("Node.js module path: " + nodeModules)
    val fullEnv = Map("NODE_PATH" -> nodeModules) ++ env
    val config =
      NodeJSConfig().withExecutable(nodePath).withCwd(Some(baseDirectory)).withEnv(fullEnv)
    val nodeEnv =
      if (!jsConfig.jsdom.contains(true)) new NodeJSEnv(logger, config)
      else new JsDomNodeJsEnv(logger, config)

    // The order of the scripts mandates the load order in the JavaScript runtime
    val input = jsConfig.kind match {
      case ModuleKindJS.NoModule => Input.Script(jsPath)
      case ModuleKindJS.CommonJSModule => Input.CommonJSModule(jsPath)
    }

    val testConfig = TestAdapter.Config().withLogger(new Logger(logger))
    val adapter = new TestAdapter(nodeEnv, Seq(input), testConfig)
    val result = adapter.loadFrameworks(frameworkNames).flatMap(_.toList)
    (result, () => adapter.close())
  }
}
