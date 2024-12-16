package bloop.scalajs

import java.nio.file.Path

import scala.collection.concurrent.TrieMap
import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration
import scala.ref.SoftReference

import bloop.config.Config.JsConfig
import bloop.config.Config.LinkerMode
import bloop.config.Config.ModuleKindJS
import bloop.data.Project
import bloop.logging.DebugFilter
import bloop.logging.{Logger => BloopLogger}

import org.scalajs.jsenv.Input
import org.scalajs.jsenv.jsdomnodejs.JSDOMNodeJSEnv
import org.scalajs.jsenv.nodejs.NodeJSEnv
import org.scalajs.linker.PathIRContainer
import org.scalajs.linker.PathOutputDirectory
import org.scalajs.linker.StandardImpl
import org.scalajs.linker.interface.{ModuleKind => ScalaJSModuleKind}
import org.scalajs.linker.interface.{ModuleSplitStyle => ScalaJSModuleKindSplitStyle, _}
import org.scalajs.logging.Level
import org.scalajs.logging.{Logger => JsLogger}
import org.scalajs.testing.adapter.TestAdapter
import org.scalajs.testing.adapter.TestAdapterInitializer
import bloop.config.Config.ModuleSplitStyleJS

/**
 * Defines operations provided by the Scala.JS 1.x toolchain.
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
  private object ScalaJSLinker {
    private val cache = TrieMap.empty[Path, SoftReference[(JsConfig, Linker)]]
    def reuseOrCreate(config: JsConfig, target: Path): Linker =
      if (config.mode == LinkerMode.Release) createLinker(config)
      else
        cache.get(target) match {
          case Some(SoftReference((`config`, linker))) => linker
          case _ =>
            val newLinker = createLinker(config)
            cache.update(target, SoftReference((config, newLinker)))
            newLinker
        }
    private def createLinker(config: JsConfig): Linker = {
      val isFullLinkJS = config.mode == LinkerMode.Release
      val semantics =
        if (isFullLinkJS) Semantics.Defaults.optimized
        else Semantics.Defaults
      val scalaJSModuleKind = config.kind match {
        case ModuleKindJS.NoModule => ScalaJSModuleKind.NoModule
        case ModuleKindJS.CommonJSModule => ScalaJSModuleKind.CommonJSModule
        case ModuleKindJS.ESModule => ScalaJSModuleKind.ESModule
      }

      val scalaJSModuleKindSplitStyle: Option[ScalaJSModuleKindSplitStyle] =
        config.moduleSplitStyle.map {
          case ModuleSplitStyleJS.FewestModules => ScalaJSModuleKindSplitStyle.FewestModules
          case ModuleSplitStyleJS.SmallestModules => ScalaJSModuleKindSplitStyle.SmallestModules
          case ModuleSplitStyleJS.SmallModulesFor(packages) =>
            ScalaJSModuleKindSplitStyle.SmallModulesFor(packages)
        }

      val useClosure = isFullLinkJS && config.kind != ModuleKindJS.ESModule

      val linkerConfig = StandardConfig()
        .withClosureCompiler(useClosure)
        .withSemantics(semantics)
        .withModuleKind(scalaJSModuleKind)
        .withSourceMap(config.emitSourceMaps)
        .withMinify(isFullLinkJS)

      (config.kind, scalaJSModuleKindSplitStyle) match {
        case (ModuleKindJS.ESModule, Some(value)) =>
          StandardImpl.clearableLinker(
            linkerConfig.withModuleSplitStyle(value)
          )
        case (_, _) => StandardImpl.clearableLinker(linkerConfig)
      }
    }
  }

  def link(
      config: JsConfig,
      project: Project,
      classpath: Array[Path],
      isTest: java.lang.Boolean,
      mainClass: Option[String],
      targetDirectory: Path,
      logger: BloopLogger,
      executionContext: ExecutionContext
  ): Unit = {
    implicit val ec = executionContext
    implicit val logFilter: DebugFilter = DebugFilter.Link
    val linker = ScalaJSLinker.reuseOrCreate(config, targetDirectory)
    val cache = StandardImpl.irFileCache().newCache
    val irContainersPairs = PathIRContainer.fromClasspath(classpath)
    val libraryIrsFuture = irContainersPairs.flatMap(pair => cache.cached(pair._1))

    val moduleInitializers = mainClass match {
      case Some(mainClass) =>
        logger.debug(s"Setting up main module initializers for $project")
        List(ModuleInitializer.mainMethodWithArgs(mainClass, "main"))
      case _ =>
        if (!isTest) {
          logger.debug(s"Setting up no module initializers, commonjs module detected $project")
          Nil
        } else {
          // There is no main class, install the test module initializers
          logger.debug(s"Setting up test module initializers for $project")
          ModuleInitializer.mainMethod(
            TestAdapterInitializer.ModuleClassName,
            TestAdapterInitializer.MainMethodName
          ) :: Nil
        }
    }

    val resultFuture = for {
      libraryIRs <- libraryIrsFuture
      _ <- linker.link(
        libraryIRs,
        moduleInitializers,
        PathOutputDirectory(targetDirectory),
        new Logger(logger)
      )
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
    val nodeModules = baseDirectory.resolve("node_modules")
    if (nodeModules.toFile().exists()) {
      logger.debug("Node.js module path: " + nodeModules.toString())
      val fullEnv = Map("NODE_PATH" -> nodeModules.toString()) ++ env
      val nodeEnv =
        if (!jsConfig.jsdom.contains(true))
          new NodeJSEnv(
            NodeJSEnv.Config().withExecutable(nodePath).withEnv(fullEnv)
          )
        else new JSDOMNodeJSEnv(JSDOMNodeJSEnv.Config().withExecutable(nodePath).withEnv(fullEnv))

      // The order of the scripts mandates the load order in the JavaScript runtime
      val input = jsConfig.kind match {
        case ModuleKindJS.NoModule => Input.Script(jsPath)
        case ModuleKindJS.CommonJSModule => Input.CommonJSModule(jsPath)
        case ModuleKindJS.ESModule => Input.ESModule(jsPath)
      }

      val testConfig = TestAdapter.Config().withLogger(new Logger(logger))
      val adapter = new TestAdapter(nodeEnv, Seq(input), testConfig)
      val result = adapter.loadFrameworks(frameworkNames).flatMap(_.toList)
      (result, () => adapter.close())
    } else {
      logger.warn(
        s"Cannot discover test frameworks, missing node_modules in test project, expected them at $nodeModules"
      )
      (Nil, () => ())
    }
  }
}
