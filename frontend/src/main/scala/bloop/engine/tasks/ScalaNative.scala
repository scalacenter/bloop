package bloop.engine.tasks

import scala.util.{Failure, Success, Try}

import java.net.URLClassLoader
import java.nio.file.{Files, Path}

import bloop.{DependencyResolution, Project}
import bloop.cli.ExitStatus
import bloop.engine.State
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.logging.Logger

import monix.eval.Task

object ScalaNative {

  /**
   * Compile down to native binary using Scala Native's toolchain.
   *
   * @param project The project to link
   * @param entry   The fully qualified main class name
   * @param logger  The logger to use
   * @return The absolute path to the native binary.
   */
  def link(project: Project, entry: String, logger: Logger): Task[Try[AbsolutePath]] = {

    initializeToolChain(logger)

    val bridgeClazz = nativeClassLoader.loadClass("bloop.scalanative.NativeBridge")
    val paramTypes = classOf[Project] :: classOf[String] :: classOf[Logger] :: Nil
    val nativeLinkMeth = bridgeClazz.getMethod("nativeLink", paramTypes: _*)

    // The Scala Native toolchain expects to receive the module class' name
    val fullEntry = if (entry.endsWith("$")) entry else entry + "$"

    Task(nativeLinkMeth.invoke(null, project, fullEntry, logger)).materialize.map {
      _.collect { case path: Path => AbsolutePath(path) }
    }
  }

  /**
   * Link `project` to a native binary and run it.
   *
   * @param state The current state of Bloop.
   * @param project The project to link.
   * @param cwd     The working directory in which to start the process.
   * @param main    The fully qualified main class name.
   * @param args    The arguments to pass to the program.
   * @return A task that links and run the project.
   */
  def run(state: State,
          project: Project,
          cwd: AbsolutePath,
          main: String,
          args: Array[String]): Task[State] = {
    link(project, main, state.logger).flatMap {
      case Success(nativeBinary) =>
        val cmd = nativeBinary.syntax +: args
        Forker.run(cwd, cmd, state.logger, state.commonOptions).map { exitCode =>
          val exitStatus = {
            if (exitCode == Forker.EXIT_OK) ExitStatus.Ok
            else ExitStatus.UnexpectedError
          }
          state.mergeStatus(exitStatus)
        }
      case Failure(ex) =>
        Task {
          state.logger.error("Couldn't create native binary.")
          state.logger.trace(ex)
          state.mergeStatus(ExitStatus.UnexpectedError)
        }
    }
  }

  private def bridgeJars(logger: Logger): Array[AbsolutePath] = {
    val organization = bloop.internal.build.BuildInfo.organization
    val nativeBridge = bloop.internal.build.BuildInfo.nativeBridge
    val version = bloop.internal.build.BuildInfo.version
    logger.debug(s"Resolving Native bridge: $organization:$nativeBridge:$version")
    val files = DependencyResolution.resolve(organization, nativeBridge, version, logger)
    files.filter(_.underlying.toString.endsWith(".jar"))
  }

  private def bridgeClassLoader(parent: Option[ClassLoader], logger: Logger): ClassLoader = {
    val jars = bridgeJars(logger)
    val entries = jars.map(_.underlying.toUri.toURL)
    new URLClassLoader(entries, parent.orNull)
  }

  private[this] var nativeClassLoader: ClassLoader = _
  private[this] def initializeToolChain(logger: Logger): Unit = synchronized {
    if (nativeClassLoader == null) {
      nativeClassLoader = bridgeClassLoader(Some(this.getClass.getClassLoader), logger)
    }
  }

}
