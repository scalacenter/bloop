package bloop.engine.tasks.toolchains
import scala.annotation.nowarn

import bloop.config.Config

@nowarn("msg=parameter value classLoader in class JvmToolchain is never used")
// Contains nothing for now
final class JvmToolchain private (classLoader: ClassLoader)

object JvmToolchain extends ToolchainCompanion[JvmToolchain] {
  override final val name: String = "Scala JVM"
  override type Platform = Config.Platform.Jvm
  override type Config = Config.JvmConfig

  override def artifactNameFrom(version: String): String = ""
  override def getPlatformData(platform: Platform): Option[PlatformData] = None
  override def apply(classLoader: ClassLoader): JvmToolchain = new JvmToolchain(classLoader)
}
