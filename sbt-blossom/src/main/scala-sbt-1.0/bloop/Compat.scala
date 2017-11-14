import sbt.{Keys, SettingKey}
import sbt.librarymanagement.ScalaModuleInfo

package object bloop {
  implicit class WithIvyScala(keys: Keys.type) {
    def ivyScala: SettingKey[Option[ScalaModuleInfo]] = keys.scalaModuleInfo
  }
}
