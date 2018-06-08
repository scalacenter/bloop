package bloop.engine.tasks

import java.nio.file.Path

import bloop.Project
import bloop.cli.{ExitStatus, OptimizerConfig}
import bloop.engine.State
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.logging.Logger

import monix.eval.Task

import scala.util.{Failure, Success, Try}

class ScalaJsToolchain private (classLoader: ClassLoader) {

  /**
   * Compile down to JavaScript using Scala.js' toolchain.
   *
   * @param project   The project to link
   * @param mainClass The fully qualified main class name
   * @param logger    The logger to use
   * @param optimize  The configurtaion of the optimizer.
   * @return The absolute path to the generated JS source.
   */
  def link(project: Project,
           mainClass: String,
           logger: Logger,
           optimize: OptimizerConfig): Task[Try[AbsolutePath]] = {

    val bridgeClazz = classLoader.loadClass("bloop.scalajs.JsBridge")
    val paramTypes = classOf[Project] :: classOf[String] :: classOf[Logger] :: classOf[
      OptimizerConfig] :: Nil
    val method = bridgeClazz.getMethod("link", paramTypes: _*)

    Task(method.invoke(null, project, mainClass, logger, optimize)).materialize.map {
      _.collect { case path: Path => AbsolutePath(path) }
    }
  }

  /**
   * Compile `project` to a Javascript and run it.
   *
   * @param state    The current state of Bloop.
   * @param project  The project to link.
   * @param cwd      The working directory in which to start the process.
   * @param main     The fully qualified main class name.
   * @param args     The arguments to pass to the program.
   * @param optimize The configuration of the optimizer.
   * @return A task that compiles and run the project.
   */
  def run(state: State,
          project: Project,
          cwd: AbsolutePath,
          mainClass: String,
          args: Array[String],
          optimize: OptimizerConfig): Task[State] = {
    link(project, mainClass, state.logger, optimize).flatMap {
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

  override val toolchainArtifactName = bloop.internal.build.BuildInfo.jsBridge

  override def apply(classLoader: ClassLoader): ScalaJsToolchain = {
    new ScalaJsToolchain(classLoader)
  }

  override def forProject(project: Project, logger: Logger): ScalaJsToolchain = {
    project.jsConfig match {
      case None =>
        resolveToolchain(logger)

      case Some(config) =>
        val classpath = config.toolchainClasspath.map(AbsolutePath.apply)
        direct(classpath)
    }
  }

}
