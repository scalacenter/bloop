package bloop.engine.tasks

import java.nio.file.Path

import bloop.Project
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.config.Config.JsConfig
import bloop.engine.State
import bloop.exec.Forker
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.Logger
import monix.eval.Task

import scala.util.{Failure, Success, Try}

final class ScalaJsToolchain private (classLoader: ClassLoader) {
  private val paramTypes =
    classOf[JsConfig] :: classOf[Project] :: classOf[String] :: classOf[Path] :: classOf[Logger] :: Nil

  /**
   * Compile down to JavaScript using Scala.js' toolchain.
   *
   * @param project   The project to link
   * @param config    The configuration for Scala.js
   * @param mainClass The fully qualified main class name
   * @param target    The output file path
   * @param logger    The logger to use
   * @return The absolute path to the generated JavaScript file
   */
  def link(
      config: JsConfig,
      project: Project,
      mainClass: String,
      target: AbsolutePath,
      logger: Logger
  ): Task[Try[Unit]] = {
    val bridgeClazz = classLoader.loadClass("bloop.scalajs.JsBridge")
    val method = bridgeClazz.getMethod("link", paramTypes: _*)
    Task(
      method.invoke(null, config, project, mainClass, target.underlying, logger).asInstanceOf[Unit]
    ).materialize
  }
}

object ScalaJsToolchain extends ToolchainCompanion[ScalaJsToolchain] {
  override final val name: String = "Scala.js"
  override type Platform = Config.Platform.Js

  override def apply(classLoader: ClassLoader): ScalaJsToolchain =
    new ScalaJsToolchain(classLoader)

  private final val DefaultJsTarget = "out.js"
  def linkTargetFrom(config: JsConfig, out: AbsolutePath): AbsolutePath = {
    config.output match {
      case Some(p) => AbsolutePath(p)
      case None => out.resolve(DefaultJsTarget)
    }
  }

  /** The artifact name of this toolchain. */
  override def artifactNameFrom(version: String): String = {
    if (version.startsWith("0.6")) BuildInfo.jsBridge06
    else if (version.startsWith("1.0")) BuildInfo.jsBridge10
    else sys.error(s"Expected compatible Scala.js version [0.6, 1.0], $version given")
  }
}
