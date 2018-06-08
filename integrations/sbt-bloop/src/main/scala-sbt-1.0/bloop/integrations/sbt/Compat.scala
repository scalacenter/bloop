package bloop.integrations.sbt

import bloop.config.Config
import sbt.io.syntax.File
import sbt.{Artifact, Keys, SettingKey}
import sbt.librarymanagement.ScalaModuleInfo

object Compat {
  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }

  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)

  def generateCacheFile(s: Keys.TaskStreams, id: String) =
    s.cacheStoreFactory make id

  def toBloopArtifact(a: Artifact, f: File): Config.Artifact = {
    val checksum = a.checksum.map(c => Config.Checksum(c.digest, c.`type`))
    Config.Artifact(a.name, a.`type`, a.extension, a.classifier, checksum, f.toPath)
  }
}
