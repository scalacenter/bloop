package bloop.engine.tasks

import java.net.URLClassLoader
import java.nio.file.{Files, Path}

import bloop.{DependencyResolution, Project}
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
