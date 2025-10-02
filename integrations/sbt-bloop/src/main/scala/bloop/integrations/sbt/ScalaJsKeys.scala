package bloop.integrations.sbt

object ScalaJsKeys {
  import sbt.{SettingKey, settingKey}

  @deprecated("Use bloopScalaJSSourceMap instead.")
  val scalaJSEmitSourceMaps: SettingKey[Boolean] =
    settingKey("Proxy for Scala.js definition of `scalaJSEmitSourceMaps`")
}
