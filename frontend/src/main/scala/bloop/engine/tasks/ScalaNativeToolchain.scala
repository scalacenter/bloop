package bloop.engine.tasks

import scala.util.{Failure, Success, Try}

import java.nio.file.Path

import bloop.Project
import bloop.cli.{ExitStatus, OptimizerConfig}
import bloop.engine.State
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.logging.Logger

import monix.eval.Task

class ScalaNativeToolchain private (classLoader: ClassLoader) {

  /**
   * Compile down to native binary using Scala Native's toolchain.
   *
   * @param project  The project to link
   * @param entry    The fully qualified main class name
   * @param logger   The logger to use
   * @param optimize The configuration of the optimizer.
   * @return The absolute path to the native binary.
   */
  def link(project: Project,
           entry: String,
           logger: Logger,
           optimize: OptimizerConfig): Task[Try[AbsolutePath]] = {

    val bridgeClazz = classLoader.loadClass("bloop.scalanative.NativeBridge")
    val paramTypes = classOf[Project] :: classOf[String] :: classOf[Logger] :: classOf[
      OptimizerConfig] :: Nil
    val nativeLinkMeth = bridgeClazz.getMethod("nativeLink", paramTypes: _*)

    // The Scala Native toolchain expects to receive the module class' name
    val fullEntry = if (entry.endsWith("$")) entry else entry + "$"

    Task(nativeLinkMeth.invoke(null, project, fullEntry, logger, optimize)).materialize.map {
      _.collect { case path: Path => AbsolutePath(path) }
    }
  }

  /**
   * Link `project` to a native binary and run it.
   *
   * @param state    The current state of Bloop.
   * @param project  The project to link.
   * @param cwd      The working directory in which to start the process.
   * @param main     The fully qualified main class name.
   * @param args     The arguments to pass to the program.
   * @param optimize The configuration of the optimizer.
   * @return A task that links and run the project.
   */
  def run(state: State,
          project: Project,
          cwd: AbsolutePath,
          main: String,
          args: Array[String],
          optimize: OptimizerConfig): Task[State] = {
    link(project, main, state.logger, optimize).flatMap {
      case Success(nativeBinary) =>
        val cmd = nativeBinary.syntax +: args
        Forker.run(cwd, cmd, state.logger, state.commonOptions).map { exitCode =>
          val exitStatus = Forker.exitStatus(exitCode)
          state.mergeStatus(exitStatus)
        }
      case Failure(ex) =>
        Task {
          state.logger.error("Couldn't create native binary.")
          state.logger.trace(ex)
          state.mergeStatus(ExitStatus.LinkingError)
        }
    }
  }

}

object ScalaNativeToolchain extends ToolchainCompanion[ScalaNativeToolchain] {

  override val toolchainArtifactName = bloop.internal.build.BuildInfo.nativeBridge

  override def apply(classLoader: ClassLoader): ScalaNativeToolchain = {
    new ScalaNativeToolchain(classLoader)
  }

  override def forProject(project: Project, logger: Logger): ScalaNativeToolchain = {
    project.nativeConfig match {
      case None =>
        resolveToolchain(logger)

      case Some(config) =>
        val classpath = config.toolchainClasspath.map(AbsolutePath.apply)
        direct(classpath)
    }
  }

}
