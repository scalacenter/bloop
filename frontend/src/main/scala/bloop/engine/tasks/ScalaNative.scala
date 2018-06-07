package bloop.engine.tasks

import scala.util.{Failure, Success, Try}

import java.net.URLClassLoader
import java.nio.file.{Files, Path}
import java.util.concurrent.ConcurrentHashMap

import bloop.{DependencyResolution, Project}
import bloop.cli.{ExitStatus, OptimizerConfig}
import bloop.engine.State
import bloop.exec.Forker
import bloop.io.AbsolutePath
import bloop.logging.Logger

import monix.eval.Task

class ScalaNative private (classLoader: ClassLoader) {

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

}

object ScalaNative {

  private[this] var _ivyResolved: ScalaNative = _
  private[this] val instancesCache: ConcurrentHashMap[Array[AbsolutePath], ScalaNative] =
    new ConcurrentHashMap

  def forProject(project: Project, logger: Logger): ScalaNative = {
    project.nativeConfig match {
      case None =>
        ivyResolved(logger)
      case Some(config) =>
        val classpath = config.toolchainClasspath.map(AbsolutePath.apply)
        direct(classpath)
    }
  }

  def ivyResolved(logger: Logger): ScalaNative = synchronized {
    if (_ivyResolved == null) {
      val jars = bridgeJars(logger)
      val nativeClassLoader = toClassLoader(jars)
      _ivyResolved = new ScalaNative(nativeClassLoader)
    }
    _ivyResolved
  }

  def direct(classpath: Array[AbsolutePath]): ScalaNative = {
    instancesCache.computeIfAbsent(classpath,
                                   classpath => new ScalaNative(toClassLoader(classpath)))
  }

  private def bridgeJars(logger: Logger): Array[AbsolutePath] = {
    val organization = bloop.internal.build.BuildInfo.organization
    val nativeBridge = bloop.internal.build.BuildInfo.nativeBridge
    val version = bloop.internal.build.BuildInfo.version
    logger.debug(s"Resolving Native bridge: $organization:$nativeBridge:$version")
    val files = DependencyResolution.resolve(organization, nativeBridge, version, logger)
    files.filter(_.underlying.toString.endsWith(".jar"))
  }

  private def toClassLoader(classpath: Array[AbsolutePath]): ClassLoader = {
    val parent = this.getClass.getClassLoader
    val entries = classpath.map(_.underlying.toUri.toURL)
    new URLClassLoader(entries, parent)
  }

}
