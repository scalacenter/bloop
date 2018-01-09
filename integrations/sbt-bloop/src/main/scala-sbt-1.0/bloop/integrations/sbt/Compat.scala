package bloop.integrations.sbt

import sbt.io.syntax.File
import sbt.{Keys, SettingKey}
import sbt.librarymanagement.ScalaModuleInfo

object Compat {
  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }

  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)
}
