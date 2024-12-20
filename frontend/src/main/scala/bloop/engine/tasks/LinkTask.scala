package bloop.engine.tasks

import bloop.cli.Commands.LinkingCommand
import bloop.cli.ExitStatus
import bloop.cli.OptimizerConfig
import bloop.config.Config
import bloop.config.Config.LinkerMode
import bloop.data.Platform
import bloop.data.Project
import bloop.engine.Feedback
import bloop.engine.State
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.engine.tasks.toolchains.ScalaNativeToolchain
import bloop.io.AbsolutePath
import bloop.task.Task

object LinkTask {

  def linkJS(
      cmd: LinkingCommand,
      project: Project,
      state: State,
      isTest: Boolean,
      mainClass: Option[String],
      targetDirectory: AbsolutePath,
      platform: Platform.Js,
      overrideLikerMode: Option[LinkerMode]
  ): Task[State] = {
    import state.logger
    val config0 = platform.config
    platform.toolchain match {
      case Some(toolchain) =>
        config0.output.flatMap(Tasks.reasonOfInvalidPath(_, ".js")) match {
          case Some(msg) => Task.now(state.withError(msg, ExitStatus.LinkingError))
          case None =>
            val dag = state.build.getDagFor(project)
            val fullClasspath = project.fullRuntimeClasspath(dag, state.client).map(_.underlying)
            val config =
              config0.copy(mode = getOptimizerMode(cmd.optimize, config0.mode, overrideLikerMode))

            // Pass in the default scheduler used by this task to the linker
            Task.deferAction { s =>
              toolchain
                .link(
                  config,
                  project,
                  fullClasspath,
                  isTest,
                  mainClass,
                  targetDirectory,
                  s,
                  logger
                )
                .map {
                  case scala.util.Success(_) =>
                    state.withInfo(s"Generated JavaScript file '${targetDirectory.syntax}'")
                  case scala.util.Failure(t) =>
                    val msg = Feedback.failedToLink(project, ScalaJsToolchain.name, t)
                    state.withError(msg, ExitStatus.LinkingError).withTrace(t)
                }
            }
        }
      case None =>
        val artifactName = ScalaJsToolchain.artifactNameFrom(config0.version)
        val msg = Feedback.missingLinkArtifactFor(project, artifactName, ScalaJsToolchain.name)
        Task.now(state.withError(msg))
    }
  }

  def linkNative(
      cmd: LinkingCommand,
      project: Project,
      state: State,
      mainClass: Option[String],
      target: AbsolutePath,
      platform: Platform.Native,
      overrideLikerMode: Option[LinkerMode]
  ): Task[State] = {
    val config0 = platform.config
    platform.toolchain match {
      case Some(toolchain) =>
        config0.output.flatMap(Tasks.reasonOfInvalidPath(_)) match {
          case Some(msg) => Task.now(state.withError(msg, ExitStatus.LinkingError))
          case None =>
            val dag = state.build.getDagFor(project)
            val fullClasspath = project.fullRuntimeClasspath(dag, state.client).map(_.underlying)
            val config =
              config0.copy(mode = getOptimizerMode(cmd.optimize, config0.mode, overrideLikerMode))
            toolchain.link(config, project, fullClasspath, mainClass, target, state.logger) map {
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
      fallbackMode: Config.LinkerMode,
      overrideLikerMode: Option[LinkerMode]
  ): Config.LinkerMode = {
    overrideLikerMode match {
      case None =>
        config match {
          case Some(OptimizerConfig.Debug) => Config.LinkerMode.Debug
          case Some(OptimizerConfig.Release) => Config.LinkerMode.Release
          case None => fallbackMode
        }
      case Some(mode) => mode
    }

  }
}
