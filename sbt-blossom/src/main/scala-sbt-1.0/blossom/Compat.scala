//package blossom

import sbt.{Keys, SettingKey}
import sbt.librarymanagement.ScalaModuleInfo

package object blossom {

  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }

}
