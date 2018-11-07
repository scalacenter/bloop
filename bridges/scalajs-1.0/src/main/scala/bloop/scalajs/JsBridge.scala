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
 * The 1.x js bridge needs to inline the implementation of `NodeJSEnv` and
 * `JSDOMNodeJSEnv` because the protected `customInitFiles` method has been
 * removed after significant refactorings in the 1.x test infrastructure.
 * This method is critical to run tests via Scala.JS because it allowed us
 * to install a script that would set the current working directory of the
 * node processes to the project working directory (see `JsBridge` for 0.6).
 *
 * As this is not exposed in the jsenv's public interfaces anymore, we have
 * inline the most common environments *and* use nuprocess to set the cwd.
 * The use of nuprocess is not strictly necessary but we do it to be consistent
 * with the rest of the codebase. Whenever Scala.JS fixes this issue upstream,
 * we can consider removing the additional jsenv code and use the `scala.sys.process`
 * that `NodeJSEnv` et al use under the hood. Note that its use does not prevent
 * us from being able to cancel the tests accordingly, as the `close` method will
 * correctly end all the existing communications and kill the js runs forcibly.
 */
object JsBridge {
  private class Logger(logger: BloopLogger) extends JsLogger {
    override def log(level: Level, message: => String): Unit =
      level match {
        case Level.Error => logger.error(message)
        case Level.Warn => logger.warn(message)
        case Level.Info => logger.info(message)
        case Level.Debug => logger.debug(message)(DebugFilter.Link)
      }
    override def success(message: => String): Unit = logger.info(message)
    override def trace(t: => Throwable): Unit = logger.trace(t)
  }

  def link(
      config: JsConfig,
      project: Project,
      mainClass: Option[String],
      target: Path,
      logger: BloopLogger
  ): Unit = {
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
    val irClasspath = FileScalaJSIRContainer.fromClasspath(project.classpath.map(_.toFile))
    val irFiles = cache.cached(irClasspath)

    val moduleInitializers = mainClass match {
      case Some(mainClass) => List(ModuleInitializer.mainMethodWithArgs(mainClass, "main"))
      case None => // There is no main class
        import org.scalajs.testadapter.TestAdapterInitializer
        List(
          ModuleInitializer.mainMethod(
            TestAdapterInitializer.ModuleClassName,
            TestAdapterInitializer.MainMethodName
          )
        )
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
      projectPath: Path,
      logger: BloopLogger,
      jsdom: java.lang.Boolean,
      env: Map[String, String]
  ): (List[sbt.testing.Framework], () => Unit) = {
    val config = NodeJSConfig().withExecutable(nodePath).withCwd(Some(projectPath)).withEnv(env)
    val nodeEnv =
      if (!jsdom) new NodeJSEnv(logger, config)
      else new JsDomNodeJsEnv(logger, config)

    val testConfig = TestAdapter.Config().withLogger(new Logger(logger))
    val adapter = new TestAdapter(
      nodeEnv,
      Input.ScriptsToLoad(
        List(
          MemVirtualBinaryFile
            .fromStringUTF8(jsPath.toString, scala.io.Source.fromFile(jsPath.toFile).mkString)
        )
      ),
      testConfig
    )

    val result = adapter.loadFrameworks(frameworkNames).flatMap(_.toList)
    (result, () => adapter.close())
  }
}
