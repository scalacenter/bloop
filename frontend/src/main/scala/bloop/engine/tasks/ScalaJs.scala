package bloop.engine.tasks

import java.io.File

import coursier.maven.MavenRepository

import bloop.Project
import bloop.logging.Logger

object ScalaJs {

  private var classLoader: ClassLoader = _

  /**
    * Compile down to JavaScript using Scala.js' toolchain.
    *
    * @param project    The project to link
    * @param mainClass  The fully qualified main class name
    * @param target     The target path
    * @param optimise   Perform code optimisations
    * @param logger     The logger to use
    */
  def link(project  : Project,
           mainClass: String,
           target   : File,
           optimise : Boolean,
           logger   : Logger): Unit = {
    // Bintray repository is needed for lsp4s
    if (classLoader == null)
      classLoader = BridgeClassLoader.classLoader(
        logger, bloop.internal.build.BuildInfo.jsBridge,
        Seq(MavenRepository("https://dl.bintray.com/scalameta/maven/")))

    val bridgeClazz = classLoader.loadClass("bloop.scalajs.JsBridge")
    val paramTypes =
      classOf[Project] :: classOf[String] :: classOf[File] ::
      classOf[java.lang.Boolean] :: classOf[Logger] :: Nil
    val method = bridgeClazz.getMethod("link", paramTypes: _*)

    val _ = method.invoke(null, project, mainClass, target,
      optimise: java.lang.Boolean, logger)
  }

}
