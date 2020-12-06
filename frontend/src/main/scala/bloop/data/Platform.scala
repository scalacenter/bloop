package bloop.data

import bloop.config.Config
import bloop.engine.tasks.toolchains.{JvmToolchain, ScalaJsToolchain, ScalaNativeToolchain}
import bloop.io.AbsolutePath

sealed trait Platform {
  def userMainClass: Option[String]
}

object Platform {
  final case class Jvm(
      config: JdkConfig,
      toolchain: JvmToolchain,
      userMainClass: Option[String],
      runtimeConfig: Option[JdkConfig],
      classpath: List[AbsolutePath],
      resources: List[AbsolutePath]
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
