package bloop.engine.tasks.toolchains

import java.lang.reflect.InvocationTargetException
import java.nio.file.Path

import bloop.DependencyResolution
import bloop.config.Config
import bloop.config.Config.NativeConfig
import bloop.data.Project
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.logging.Logger
import monix.eval.Task

import scala.util.Try

final class ScalaNativeToolchain private (classLoader: ClassLoader) {

  /**
   * Compile down to native binary using Scala Native's toolchain.
   *
   * @param config        The native configuration to use.
   * @param project       The project to link
   * @param fullClasspath The full classpath to link with
   * @param mainClass     The fully qualified main class name
   * @param logger        The logger to use
   * @return The absolute path to the native binary.
   */
  def link(
      config: NativeConfig,
      project: Project,
      fullClasspath: Array[Path],
      mainClass: String,
      target: AbsolutePath,
      logger: Logger
  ): Task[Try[Unit]] = {
    val bridgeClazz = classLoader.loadClass("bloop.scalanative.NativeBridge")
    val nativeLinkMeth = bridgeClazz.getMethod("nativeLink", paramTypes: _*)

    // The Scala Native toolchain expects to receive the module class' name
    val fullEntry = if (mainClass.endsWith("$")) mainClass else mainClass + "$"
    val linkage = Task {
      nativeLinkMeth
        .invoke(null, config, project, fullClasspath, fullEntry, target.underlying, logger)
        .asInstanceOf[Unit]
    }.materialize

    linkage.map {
      case s @ scala.util.Success(_) => s
      case f @ scala.util.Failure(t) =>
        t match {
          case it: InvocationTargetException => scala.util.Failure(it.getCause)
          case _ => f
        }
    }
  }

  // format: OFF
  private val paramTypes = classOf[NativeConfig] :: classOf[Project] :: classOf[Array[Path]] :: classOf[String] :: classOf[Path] :: classOf[Logger] :: Nil
  // format: ON
}

object ScalaNativeToolchain extends ToolchainCompanion[ScalaNativeToolchain] {
  override final val name: String = "Scala Native"
  override type Platform = Config.Platform.Native
  override type Config = Config.NativeConfig

  override def artifactNameFrom(version: String): String = {
    if (version.length == 3) sys.error("The full Scala Native version must be provided")
    else if (version.startsWith("0.3")) BuildInfo.nativeBridge03
    else if (version.startsWith("0.4")) BuildInfo.nativeBridge04
    else sys.error(s"Expected compatible Scala Native version [0.3, 0.4], $version given")
  }

  override def getPlatformData(platform: Platform): Option[PlatformData] = {
    val artifactName = artifactNameFrom(platform.config.version)
    val platformVersion = platform.config.version
    val scalaVersion = DependencyResolution.majorMinorVersion(BuildInfo.scalaVersion)

    val artifacts = List(
      DependencyResolution.Artifact(BuildInfo.organization, artifactName, BuildInfo.version),
      DependencyResolution.Artifact("org.scala-native", s"tools_$scalaVersion", platformVersion)
    )

    Some(PlatformData(artifacts, platform.config.toolchain))
  }

  def linkTargetFrom(project: Project, config: NativeConfig): AbsolutePath = {
    config.output match {
      case Some(p) => AbsolutePath(p)
      case None => project.out.resolve(s"${project.name}.out")
    }
  }

  override def apply(classLoader: ClassLoader): ScalaNativeToolchain =
    new ScalaNativeToolchain(classLoader)
}
