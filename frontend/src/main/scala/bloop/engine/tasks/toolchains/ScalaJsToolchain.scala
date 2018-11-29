package bloop.engine.tasks.toolchains

import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.nio.file.Path

import bloop.config.Config
import bloop.config.Config.JsConfig
import bloop.data.Project
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.testing.{DiscoveredTestFrameworks, TestInternals}
import monix.eval.Task

import scala.util.Try

/**
 * Defines a set of tasks that the Scala.js toolchain can execute.
 *
 * The tasks must be supported by different versions (e.g. 0.6.x vs 1.x)
 * and they are invoked reflectively based on the bridges defined by
 * bloop's `jsBridge` modules.
 *
 * @param bridgeClassLoader The classloader that contains the bridges and
 *                          the classes that implement the toolchain.
 */
final class ScalaJsToolchain private (bridgeClassLoader: ClassLoader) {

  /**
   * Link (compile down to JavaScript) using Scala.js' toolchain.
   *
   * If the main class is not passed, the link implementation will assume
   * that this is a test project and will therefore set up the test module
   * initializers instead of the main module initializers.
   *
   * @param project The project to link
   * @param config The configuration for Scala.js
   * @param runMain Whether the link process should install module initializers for main.
   * @param mainClass The main class if invoked via `link` or `run`.
   * @param target The output file path
   * @param logger An instance of a logger.
   * @return An instance of a try if the method has succeeded.
   */
  def link(
      config: JsConfig,
      project: Project,
      runMain: java.lang.Boolean,
      mainClass: Option[String],
      target: AbsolutePath,
      logger: Logger
  ): Task[Try[Unit]] = {
    val bridgeClazz = bridgeClassLoader.loadClass("bloop.scalajs.JsBridge")
    val method = bridgeClazz.getMethod("link", paramTypesLink: _*)
    val linkage = Task(
      method
        .invoke(null, config, project, runMain, mainClass, target.underlying, logger)
        .asInstanceOf[Unit]
    ).materialize
    linkage.map {
      case s @ scala.util.Success(_) => s
      case f @ scala.util.Failure(t) =>
        t match {
          case it: InvocationTargetException => scala.util.Failure(it.getCause)
          case _ => f
        }
    }
  }

  /**
   * Discovers Scala.js compatible test frameworks.
   *
   * @param project The project in which to discover test frameworks
   * @param frameworkNames The names of the potential frameworks in the project
   * @param linkedFile Path to test project's linked JavaScript file
   *                   @param logger An instance of a logger.
   */
  def discoverTestFrameworks(
      project: Project,
      frameworkNames: List[List[String]],
      linkedFile: AbsolutePath,
      logger: Logger,
      config: JsConfig,
      env: Map[String, String]
  ): DiscoveredTestFrameworks.Js = {
    val baseDir = project.baseDirectory.underlying
    val bridgeClazz = bridgeClassLoader.loadClass("bloop.scalajs.JsBridge")
    val method = bridgeClazz.getMethod("discoverTestFrameworks", paramTypesTestFrameworks: _*)
    val dom = Boolean.box(config.jsdom.getOrElse(false))
    val node = config.nodePath.map(_.toAbsolutePath.toString).getOrElse("node")
    val (frameworks, closeResources) = method
      .invoke(null, frameworkNames, node, linkedFile.underlying, baseDir, logger, dom, env)
      .asInstanceOf[(List[sbt.testing.Framework], ScalaJsToolchain.CloseResources)]

    DiscoveredTestFrameworks.Js(frameworks, closeResources)
  }

  // format: OFF
  private val paramTypesLink = classOf[JsConfig] :: classOf[Project] :: classOf[java.lang.Boolean] :: classOf[Option[String]] :: classOf[Path] :: classOf[Logger] :: Nil
  private val paramTypesTestFrameworks = classOf[List[List[String]]] :: classOf[String] :: classOf[Path] :: classOf[Path] :: classOf[Logger] :: classOf[java.lang.Boolean] :: classOf[Map[String, String]] :: Nil
  // format: ON
}

object ScalaJsToolchain extends ToolchainCompanion[ScalaJsToolchain] {
  type CloseResources = () => Unit

  override final val name: String = "Scala.js"
  override type Platform = Config.Platform.Js
  override type Config = Config.JsConfig

  override def apply(classLoader: ClassLoader): ScalaJsToolchain =
    new ScalaJsToolchain(classLoader)

  def linkTargetFrom(project: Project, config: JsConfig): AbsolutePath = {
    config.output match {
      case Some(p) => AbsolutePath(p)
      case None => project.out.resolve(s"${project.name}.js")
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
