package bloop.integrations.config

import java.nio.file.Path

import bloop.config.ConfigSchema.{
  JavaConfig,
  JvmConfig,
  ProjectConfig,
  ScalaConfig,
  TestArgumentConfig,
  TestConfig,
  TestFrameworkConfig,
  TestOptionsConfig
}

import io.circe.{Encoder, Json}
import io.circe.derivation.deriveEncoder

object CirceEncoders {
  implicit val pathEncoder: Encoder[Path] = new Encoder[Path] {
    override final def apply(a: Path): Json = Json.fromString(a.toString)
  }

  implicit val javaConfigEncoder: Encoder[JavaConfig] = deriveEncoder
  implicit val jvmConfigEncoder: Encoder[JvmConfig] = deriveEncoder
  implicit val testFrameworkConfigEncoder: Encoder[TestFrameworkConfig] = deriveEncoder
  implicit val testArgumentConfigEncoder: Encoder[TestArgumentConfig] = deriveEncoder
  implicit val testOptionsConfigEncoder: Encoder[TestOptionsConfig] = deriveEncoder
  implicit val testConfigEncoder: Encoder[TestConfig] = deriveEncoder
  implicit val scalaConfigEncoder: Encoder[ScalaConfig] = deriveEncoder
  implicit val projectConfigEncoder: Encoder[ProjectConfig] = deriveEncoder
}
