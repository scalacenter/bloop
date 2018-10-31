package bloop.engine.tasks

import bloop.cli.Commands.LinkingCommand
import bloop.cli.{ExitStatus, OptimizerConfig}
import bloop.config.Config
import bloop.data.{Platform, Project}
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import bloop.engine.{Feedback, State}
import bloop.io.AbsolutePath
import monix.eval.Task

object LinkTask {
  def linkMainWithJs(
      cmd: LinkingCommand,
      project: Project,
      state: State,
      mainClass: String,
      target: AbsolutePath,
      platform: Platform.Js,
  ): Task[State] = {
    val config0 = platform.config
    platform.toolchain match {
      case Some(toolchain) =>
        config0.output.flatMap(Tasks.reasonOfInvalidPath(_, ".js")) match {
          case Some(msg) => Task.now(state.withError(msg, ExitStatus.LinkingError))
          case None =>
            val config = config0.copy(mode = getOptimizerMode(cmd.optimize, config0.mode))
            toolchain.link(config, project, Some(mainClass), target, state.logger) map {
              case scala.util.Success(_) =>
                state.withInfo(s"Generated JavaScript file '${target.syntax}'")
              case scala.util.Failure(t) =>
                val msg = Feedback.failedToLink(project, ScalaJsToolchain.name, t)
                state.withError(msg, ExitStatus.LinkingError).withTrace(t)
            }
        }
      case None =>
        val artifactName = ScalaJsToolchain.artifactNameFrom(config0.version)
        val msg = Feedback.missingLinkArtifactFor(project, artifactName, ScalaJsToolchain.name)
        Task.now(state.withError(msg))
    }
  }

  def linkMainWithNative(
      cmd: LinkingCommand,
      project: Project,
      state: State,
      mainClass: String,
      target: AbsolutePath,
      platform: Platform.Native
  ): Task[State] = {
    val config0 = platform.config
    platform.toolchain match {
      case Some(toolchain) =>
        config0.output.flatMap(Tasks.reasonOfInvalidPath(_)) match {
          case Some(msg) => Task.now(state.withError(msg, ExitStatus.LinkingError))
          case None =>
            val config = config0.copy(mode = getOptimizerMode(cmd.optimize, config0.mode))
            toolchain.link(config, project, mainClass, target, state.logger) map {
              case scala.util.Success(_) =>
                state.withInfo(s"Generated native binary '${target.syntax}'")
              case scala.util.Failure(t) =>
                val msg = Feedback.failedToLink(project, ScalaNativeToolchain.name, t)
                state.withError(msg, ExitStatus.LinkingError).withTrace(t)
            }
        }

      case None =>
        val artifactName = ScalaNativeToolchain.artifactNameFrom(config0.version)
        val msg = Feedback.missingLinkArtifactFor(project, artifactName, ScalaNativeToolchain.name)
        Task.now(state.withError(msg))
    }
  }

  private def getOptimizerMode(
      config: Option[OptimizerConfig],
      fallbackMode: Config.LinkerMode
  ): Config.LinkerMode = {
    config match {
      case Some(OptimizerConfig.Debug) => Config.LinkerMode.Debug
      case Some(OptimizerConfig.Release) => Config.LinkerMode.Release
      case None => fallbackMode
    }
  }
}
