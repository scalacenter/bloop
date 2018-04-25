package bloop.engine.tasks

import java.net.URLClassLoader
import java.nio.file.Path

import monix.eval.Task

import bloop.{DependencyResolution, Project}
import bloop.cli.ExitStatus
import bloop.engine.State
import bloop.exec.{Forker, Process}
import bloop.io.AbsolutePath
import bloop.logging.Logger

object ScalaNative {

  /**
   * Compile down to native binary using Scala Native's toolchain.
   *
   * @param project The project to link
   * @param entry   The fully qualified main class name
   * @param logger  The logger to use
   * @return The absolute path to the native binary.
   */
  def nativeLink(project: Project, entry: String, logger: Logger): AbsolutePath = {

    initializeToolChain(logger)

    val bridgeClazz = nativeClassLoader.loadClass("bloop.scalanative.NativeBridge")
    val paramTypes = classOf[Project] :: classOf[String] :: classOf[Logger] :: Nil
    val nativeLinkMeth = bridgeClazz.getMethod("nativeLink", paramTypes: _*)

    // The Scala Native toolchain expects to receive the module class' name
    val fullEntry = if (entry.endsWith("$")) entry else entry + "$"

    AbsolutePath(nativeLinkMeth.invoke(null, project, fullEntry, logger).asInstanceOf[Path])
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
          args: Array[String]): Task[State] = Task {
    import scala.collection.JavaConverters.propertiesAsScalaMap
    val nativeBinary = nativeLink(project, main, state.logger)
    val env = propertiesAsScalaMap(state.commonOptions.env).toMap
    val exitCode = Process.run(cwd, nativeBinary.syntax +: args, env, state.logger)

    val exitStatus =
      if (exitCode == Forker.EXIT_OK) ExitStatus.Ok
      else ExitStatus.UnexpectedError
    state.mergeStatus(exitStatus)
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
