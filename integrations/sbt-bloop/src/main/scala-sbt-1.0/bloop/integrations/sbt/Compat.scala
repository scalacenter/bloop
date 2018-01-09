package bloop.integrations.sbt

import bloop.integrations.sbt

object Compat {
  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }

  implicit def fileToRichFile(file: File): sbt.RichFile = new sbt.RichFile(file)
}
