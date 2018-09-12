package bloop.engine.tasks

import java.nio.file.Path

import bloop.Project
import bloop.config.Config
import bloop.config.Config.NativeConfig
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.Logger
import monix.eval.Task

import scala.util.Try

final class ScalaNativeToolchain private (classLoader: ClassLoader) {
  private val paramTypes =
    classOf[NativeConfig] :: classOf[Project] :: classOf[String] :: classOf[Path] :: classOf[Logger] :: Nil

  /**
   * Compile down to native binary using Scala Native's toolchain.
   *
   * @param config    The native configuration to use.
   * @param project   The project to link
   * @param mainClass The fully qualified main class name
   * @param logger    The logger to use
   * @return The absolute path to the native binary.
   */
  def link(
      config: NativeConfig,
      project: Project,
      mainClass: String,
      target: AbsolutePath,
      logger: Logger
  ): Task[Try[Unit]] = {
    val bridgeClazz = classLoader.loadClass("bloop.scalanative.NativeBridge")
    val nativeLinkMeth = bridgeClazz.getMethod("nativeLink", paramTypes: _*)

    // The Scala Native toolchain expects to receive the module class' name
    val fullEntry = if (mainClass.endsWith("$")) mainClass else mainClass + "$"
    Task {
      nativeLinkMeth
        .invoke(null, config, project, fullEntry, target.underlying, logger)
        .asInstanceOf[Unit]
    }.materialize
  }
}

object ScalaNativeToolchain extends ToolchainCompanion[ScalaNativeToolchain] {
  override final val name: String = "Scala Native"
  override type Platform = Config.Platform.Native
  override def artifactNameFrom(version: String): String = BuildInfo.nativeBridge

  private final val DefaultNativeTarget = "out"
  def linkTargetFrom(config: NativeConfig, out: AbsolutePath): AbsolutePath = {
    config.output match {
      case Some(p) => AbsolutePath(p)
      case None => out.resolve(DefaultNativeTarget)
    }
  }

  override def apply(classLoader: ClassLoader): ScalaNativeToolchain =
    new ScalaNativeToolchain(classLoader)
}
