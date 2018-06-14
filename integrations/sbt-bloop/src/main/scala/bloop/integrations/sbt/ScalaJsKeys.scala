package bloop.integrations.sbt

object ScalaJsKeys {
  import sbt.{SettingKey, settingKey}
  val scalaJSEmitSourceMaps: SettingKey[Boolean] =
    settingKey("Proxy for Scalajs definition of `scalaJSEmitSourceMaps`.")
}
