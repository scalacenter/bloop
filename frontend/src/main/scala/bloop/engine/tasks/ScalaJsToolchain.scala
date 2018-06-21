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

class ScalaJsToolchain private (classLoader: ClassLoader) {

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
    val paramTypes = classOf[JsConfig] :: classOf[Project] :: classOf[String] :: classOf[Path] :: classOf[Logger] :: Nil

    val method = bridgeClazz.getMethod("link", paramTypes: _*)
    Task(method.invoke(null, config, project, mainClass, target.underlying, logger)
      .asInstanceOf[Unit]).materialize
  }

  /**
   * Compile `project` to a JavaScript and run it.
   *
   * @param state     The current state of Bloop
   * @param project   The project to link
   * @param config    The configuration for Scala.js
   * @param cwd       The working directory in which to start the process
   * @param mainClass The fully qualified main class name
   * @param target    The output file path
   * @param args      The arguments to pass to the program
   * @return A task that compiles and run the project
   */
  def run(
      state: State,
      config: JsConfig,
      project: Project,
      cwd: AbsolutePath,
      mainClass: String,
      target: AbsolutePath,
      args: Array[String]
  ): Task[State] = {
    link(config, project, mainClass, target, state.logger).flatMap {
      case Success(_) =>
        val cmd = "node" +: target.syntax +: args
        Forker.run(cwd, cmd, state.logger, state.commonOptions).map { exitCode =>
          val exitStatus = Forker.exitStatus(exitCode)
          state.mergeStatus(exitStatus)
        }
      case Failure(ex) =>
        Task {
          state.logger.error("Could not generate JavaScript file")
          state.logger.trace(ex)
          state.mergeStatus(ExitStatus.LinkingError)
        }
    }
  }

}

object ScalaJsToolchain extends ToolchainCompanion[ScalaJsToolchain] {
  override type Platform = Config.Platform.Js

  override def apply(classLoader: ClassLoader): ScalaJsToolchain =
    new ScalaJsToolchain(classLoader)

  /** The artifact name of this toolchain. */
  override def artifactNameFrom(version: String): String = {
    if (version.startsWith("0.6")) BuildInfo.jsBridge06
    else if (version.startsWith("1.0")) BuildInfo.jsBridge10
    else sys.error(s"Expected compatible Scala.js version [0.6, 1.0], $version given")
  }
}
