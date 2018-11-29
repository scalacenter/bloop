package bloop.scalajs

import java.nio.file.Path

import bloop.config.Config.{JsConfig, LinkerMode, ModuleKindJS}
import bloop.data.Project
import bloop.logging.{DebugFilter, Logger => BloopLogger}
import bloop.scalajs.jsenv.{JsDomNodeJsEnv, NodeJSConfig, NodeJSEnv}
import org.scalajs.io.{AtomicWritableFileVirtualBinaryFile, MemVirtualBinaryFile}
import org.scalajs.jsenv.Input
import org.scalajs.linker.irio.{FileScalaJSIRContainer, IRFileCache}
import org.scalajs.linker.{LinkerOutput, ModuleInitializer, ModuleKind, Semantics, StandardLinker}
import org.scalajs.logging.{Level, Logger => JsLogger}
import org.scalajs.testadapter.TestAdapter

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
    override def success(message: => String): Unit = logger.info(message)
    override def trace(t: => Throwable): Unit = logger.trace(t)
  }

  def link(
      config: JsConfig,
      project: Project,
      runMain: java.lang.Boolean,
      mainClass: Option[String],
      target: Path,
      logger: BloopLogger
  ): Unit = {
    implicit val logFilter: DebugFilter = DebugFilter.Link
    val enableOptimizer = config.mode == LinkerMode.Release
    val semantics = config.mode match {
      case LinkerMode.Debug => Semantics.Defaults
      case LinkerMode.Release => Semantics.Defaults.optimized
    }

    val moduleKind = config.kind match {
      case ModuleKindJS.NoModule => ModuleKind.NoModule
      case ModuleKindJS.CommonJSModule => ModuleKind.CommonJSModule
    }

    val cache = new IRFileCache().newCache
    val irClasspath =
      FileScalaJSIRContainer.fromClasspath(project.compilationClasspath.map(_.toFile))
    val irFiles = cache.cached(irClasspath)

    val moduleInitializers = mainClass match {
      case Some(mainClass) if runMain =>
        logger.debug(s"Setting up main module initializers for ${project}")
        List(ModuleInitializer.mainMethodWithArgs(mainClass, "main"))
      case _ =>
        if (runMain) {
          moduleKind match {
            case ModuleKind.CommonJSModule =>
              logger.debug(s"Setting up no module initializers, commonjs detected in ${project}")
              Nil // If run is disabled, it'a commonjs module and we link with exports
            case ModuleKind.NoModule =>
              logger.debug(s"Setting up no module initializers in ${project}, main class is empty")
              Nil // If run is disabled, it'a commonjs module and we link with exports
          }
        } else {
          // There is no main class, install the test module initializers
          logger.debug(s"Setting up test module initializers for ${project}")
          import org.scalajs.testadapter.TestAdapterInitializer
          List(
            ModuleInitializer.mainMethod(
              TestAdapterInitializer.ModuleClassName,
              TestAdapterInitializer.MainMethodName
            )
          )
        }
    }

    val output = LinkerOutput(new AtomicWritableFileVirtualBinaryFile(target.toFile))
    val jsConfig = StandardLinker
      .Config()
      .withOptimizer(enableOptimizer)
      .withClosureCompilerIfAvailable(enableOptimizer)
      .withSemantics(semantics)
      .withModuleKind(moduleKind)
      .withSourceMap(config.emitSourceMaps)

    StandardLinker(jsConfig).link(
      irFiles = irFiles,
      moduleInitializers = moduleInitializers,
      output = output,
      logger = new Logger(logger)
    )
  }

  /** @return (list of frameworks, function to close test adapter) */
  def discoverTestFrameworks(
      frameworkNames: List[List[String]],
      nodePath: String,
      jsPath: Path,
      baseDirectory: Path,
      logger: BloopLogger,
      jsdom: java.lang.Boolean,
      env: Map[String, String]
  ): (List[sbt.testing.Framework], () => Unit) = {
    implicit val debugFilter: DebugFilter = DebugFilter.Test
    val config = NodeJSConfig().withExecutable(nodePath).withCwd(Some(baseDirectory)).withEnv(env)
    val nodeEnv =
      if (!jsdom) new NodeJSEnv(logger, config)
      else new JsDomNodeJsEnv(logger, config)

    val outputJsContents = {
      val contents = java.nio.file.Files.readAllBytes(jsPath)
      MemVirtualBinaryFile(jsPath.toString, contents)
    }

    // The order of the scripts mandates the load order in the js runtime
    val inputs = List(outputJsContents)
    val nodeModules = baseDirectory.resolve("node_modules").toString
    logger.debug("Node.js module path: " + nodeModules)
    val fullEnv = Map("NODE_PATH" -> nodeModules) ++ env

    val testConfig = TestAdapter.Config().withLogger(new Logger(logger))
    val adapter = new TestAdapter(nodeEnv, Input.ScriptsToLoad(inputs), testConfig)
    val result = adapter.loadFrameworks(frameworkNames).flatMap(_.toList)
    (result, () => adapter.close())
  }
}
