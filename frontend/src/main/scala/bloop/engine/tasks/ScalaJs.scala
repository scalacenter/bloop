package bloop.engine.tasks

import java.nio.file.Path

import bloop.Project
import bloop.cli.OptimizerConfig
import bloop.engine.State
import bloop.io.AbsolutePath
import bloop.logging.Logger

import coursier.maven.MavenRepository

import monix.eval.Task

import scala.util.Try

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
  def link(state: State, project: Project, mainClass: String, optimize: OptimizerConfig,
  ): Task[Try[AbsolutePath]] = {
    // Bintray repository is needed for lsp4s
    if (classLoader == null) {
      classLoader = BridgeClassLoader.classLoader(
        state.logger,
        bloop.internal.build.BuildInfo.jsBridge,
        Seq(MavenRepository("https://dl.bintray.com/scalameta/maven/")))
    }

    val bridgeClazz = classLoader.loadClass("bloop.scalajs.JsBridge")
    val paramTypes = classOf[Project] :: classOf[String] :: classOf[Logger] :: classOf[
      OptimizerConfig] :: Nil
    val method = bridgeClazz.getMethod("link", paramTypes: _*)

    Task(method.invoke(null, project, mainClass, state.logger, optimize)).materialize.map {
      _.collect { case path: Path => AbsolutePath(path) }
    }
  }

}
