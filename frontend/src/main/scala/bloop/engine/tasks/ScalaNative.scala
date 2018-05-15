package bloop.engine.tasks

import java.io.File

import bloop.Project
import bloop.logging.Logger

object ScalaNative {

  private var classLoader: ClassLoader = _

  /**
   * Compile down to native binary using Scala Native's toolchain.
   *
   * @param project The project to link
   * @param entry   The fully qualified main class name
   * @param logger  The logger to use
   * @return The absolute path to the native binary.
   */
  def link(project: Project,
           entry  : String,
           target : File,
           logger : Logger): Unit = {
    if (classLoader == null)
      classLoader = BridgeClassLoader.classLoader(
        logger, bloop.internal.build.BuildInfo.nativeBridge)

    val bridgeClass = classLoader.loadClass("bloop.scalanative.NativeBridge")
    val paramTypes = classOf[Project] :: classOf[String] :: classOf[File] ::
                     classOf[Logger] :: Nil
    val nativeLinkMeth = bridgeClass.getMethod("link", paramTypes: _*)

    // The Scala Native toolchain expects to receive the module class' name
    val fullEntry = if (entry.endsWith("$")) entry else entry + "$"

    val _ = nativeLinkMeth.invoke(null, project, fullEntry, target, logger)
  }

}
