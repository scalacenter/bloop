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
   * @param config    The configuration for Scalajs
   * @param mainClass The fully qualified main class name
   * @param logger    The logger to use
   * @return The absolute path to the generated JS source.
   */
  def link(
      config: JsConfig,
      project: Project,
      mainClass: String,
      logger: Logger
  ): Task[Try[AbsolutePath]] = {
    val bridgeClazz = classLoader.loadClass("bloop.scalajs.JsBridge")
    val paramTypes = classOf[JsConfig] :: classOf[Project] :: classOf[String] :: classOf[Logger] :: Nil

    val method = bridgeClazz.getMethod("link", paramTypes: _*)
    Task(method.invoke(null, config, project, mainClass, logger)).materialize.map {
      _.collect { case path: Path => AbsolutePath(path) }
    }
  }

  /**
   * Compile `project` to a Javascript and run it.
   *
   * @param state    The current state of Bloop.
   * @param project  The project to link.
   * @param config  The configuration for Scalajs.
   * @param cwd      The working directory in which to start the process.
   * @param main     The fully qualified main class name.
   * @param args     The arguments to pass to the program.
   * @return A task that compiles and run the project.
   */
  def run(
      state: State,
      config: JsConfig,
      project: Project,
      cwd: AbsolutePath,
      mainClass: String,
      args: Array[String]
  ): Task[State] = {
    link(config, project, mainClass, state.logger).flatMap {
      case Success(jsOut) =>
        val cmd = "node" +: jsOut.syntax +: args
        Forker.run(cwd, cmd, state.logger, state.commonOptions).map { exitCode =>
          val exitStatus = Forker.exitStatus(exitCode)
          state.mergeStatus(exitStatus)
        }
      case Failure(ex) =>
        Task {
          state.logger.error("Couldn't create JS output.")
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
    else sys.error(s"Expected supported Scalajs version instead of ${version}")
  }
}
