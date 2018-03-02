package bloop.integrations.config

import java.nio.file.Path

import bloop.config.Config.{
  Java,
  Jvm,
  Project,
  Scala,
  TestArgument,
  Test,
  TestFramework,
  TestOptions
}

import io.circe.{Encoder, Json}
import io.circe.derivation.deriveEncoder

object CirceEncoders {
  implicit val pathEncoder: Encoder[Path] = new Encoder[Path] {
    override final def apply(a: Path): Json = Json.fromString(a.toString)
  }

  implicit val javaConfigEncoder: Encoder[Java] = deriveEncoder
  implicit val jvmConfigEncoder: Encoder[Jvm] = deriveEncoder
  implicit val testFrameworkConfigEncoder: Encoder[TestFramework] = deriveEncoder
  implicit val testArgumentConfigEncoder: Encoder[TestArgument] = deriveEncoder
  implicit val testOptionsConfigEncoder: Encoder[TestOptions] = deriveEncoder
  implicit val testConfigEncoder: Encoder[Test] = deriveEncoder
  implicit val scalaConfigEncoder: Encoder[Scala] = deriveEncoder
  implicit val projectConfigEncoder: Encoder[Project] = deriveEncoder
}
