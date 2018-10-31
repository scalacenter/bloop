package bloop.engine.tasks.toolchains

import java.nio.file.Path

import bloop.config.Config
import bloop.config.Config.JsConfig
import bloop.data.Project
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.testing.DiscoveredTestFrameworks
import monix.eval.Task

import scala.util.Try

final class ScalaJsToolchain private (classLoader: ClassLoader) {

  /**
   * Compile down to JavaScript using Scala.js' toolchain.
   *
   * @param project   The project to link
   * @param config    The configuration for Scala.js
   * @param mainClass The fully qualified main class name; can be left empty
   *                 when linking test suites or JavaScript modules
   * @param target    The output file path
   * @param logger    The logger to use
   * @return The absolute path to the generated JavaScript file
   */
  def link(
      config: JsConfig,
      project: Project,
      mainClass: Option[String],
      target: AbsolutePath,
      logger: Logger
  ): Task[Try[Unit]] = {
    val bridgeClazz = classLoader.loadClass("bloop.scalajs.JsBridge")
    val method = bridgeClazz.getMethod("link", paramTypesLink: _*)
    Task(
      method.invoke(null, config, project, mainClass, target.underlying, logger).asInstanceOf[Unit]
    ).materialize
  }

  /**
   * @param jsPath Path to test project's linked JavaScript file
   */
  def testFrameworks(
      frameworkNames: List[List[String]],
      jsPath: AbsolutePath,
      projectPath: AbsolutePath,
      logger: Logger,
      jsdom: Boolean
  ): DiscoveredTestFrameworks.Js = {
    val bridgeClazz = classLoader.loadClass("bloop.scalajs.JsBridge")
    val method = bridgeClazz.getMethod("testFrameworks", paramTypesTestFrameworks: _*)
    val dom = Boolean.box(jsdom)
    val (frameworks, dispose) = method
      .invoke(null, frameworkNames, jsPath.underlying, projectPath.underlying, logger, dom)
      .asInstanceOf[(List[sbt.testing.Framework], ScalaJsToolchain.Dispose)]
    DiscoveredTestFrameworks.Js(frameworks, dispose)
  }

  // format: OFF
  private val paramTypesLink = classOf[JsConfig] :: classOf[Project] :: classOf[Option[String]] :: classOf[Path] :: classOf[Logger] :: Nil
  private val paramTypesTestFrameworks = classOf[List[List[String]]] :: classOf[Path] :: classOf[Path] :: classOf[Logger] :: classOf[java.lang.Boolean] :: Nil
  // format: ON
}

object ScalaJsToolchain extends ToolchainCompanion[ScalaJsToolchain] {
  type Dispose = () => Unit

  override final val name: String = "Scala.js"
  override type Platform = Config.Platform.Js
  override type Config = Config.JsConfig

  override def apply(classLoader: ClassLoader): ScalaJsToolchain =
    new ScalaJsToolchain(classLoader)

  private final val DefaultJsTarget = "out.js"
  def linkTargetFrom(config: JsConfig, out: AbsolutePath): AbsolutePath = {
    config.output match {
      case Some(p) => AbsolutePath(p)
      case None => out.resolve(DefaultJsTarget)
    }
  }

  override def artifactNameFrom(version: String): String = {
    if (version.startsWith("0.6")) BuildInfo.jsBridge06
    else if (version.startsWith("1.0")) BuildInfo.jsBridge10
    else sys.error(s"Expected compatible Scala.js version [0.6, 1.0], $version given")
  }

  override def getPlatformData(platform: Platform): Option[PlatformData] = {
    val artifactName: String = artifactNameFrom(platform.config.version)
    Some(PlatformData(artifactName, platform.config.toolchain))
  }
}
