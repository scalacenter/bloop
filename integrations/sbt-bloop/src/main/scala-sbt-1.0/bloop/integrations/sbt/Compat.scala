package bloop.integrations.sbt

import bloop.config.Config
import sbt.io.syntax.File
import sbt.{Artifact, Exec, Keys, SettingKey}
import sbt.librarymanagement.ScalaModuleInfo

object Compat {
  type PluginData = sbt.PluginData
  val PluginData = sbt.PluginData
  val PluginDiscovery = sbt.internal.PluginDiscovery
  val PluginManagement = sbt.internal.PluginManagement

  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }

  implicit def execToString(e: Exec): String = e.commandLine

  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)

  def generateCacheFile(s: Keys.TaskStreams, id: String) =
    s.cacheStoreFactory make id

  def toBloopArtifact(a: Artifact, f: File): Config.Artifact = {
    val checksum = a.checksum.map(c => Config.Checksum(c.digest, c.`type`))
    Config.Artifact(a.name, a.classifier, checksum, f.toPath)
  }

  private final val anyWriter = implicitly[sbt.util.OptJsonWriter[AnyRef]]
  def toAnyRefSettingKey(id: String, m: Manifest[AnyRef]): SettingKey[AnyRef] =
    SettingKey(id)(m, anyWriter)
}
