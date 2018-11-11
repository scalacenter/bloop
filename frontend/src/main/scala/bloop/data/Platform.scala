package bloop.data
import bloop.config.Config
import bloop.engine.tasks.toolchains.{JvmToolchain, ScalaJsToolchain, ScalaNativeToolchain}
import bloop.exec.JavaEnv

sealed trait Platform {
  def userMainClass: Option[String]
}

object Platform {
  final case class Jvm(
      env: JavaEnv,
      toolchain: JvmToolchain,
      userMainClass: Option[String]
  ) extends Platform

  final case class Js(
      config: Config.JsConfig,
      toolchain: Option[ScalaJsToolchain],
      userMainClass: Option[String]
  ) extends Platform

  final case class Native(
      config: Config.NativeConfig,
      toolchain: Option[ScalaNativeToolchain],
      userMainClass: Option[String]
  ) extends Platform
}
