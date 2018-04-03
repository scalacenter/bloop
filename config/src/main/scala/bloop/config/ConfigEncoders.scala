package bloop.config

import java.nio.file.Path

import io.circe.{Json, ObjectEncoder, RootEncoder}
import io.circe.derivation.deriveEncoder
import bloop.config.Config._

object ConfigEncoders {
  implicit val pathEncoder: RootEncoder[Path] = new RootEncoder[Path] {
    override final def apply(a: Path): Json = Json.fromString(a.toString)
  }

  implicit val javaConfigEncoder: ObjectEncoder[Java] = deriveEncoder
  implicit val jvmConfigEncoder: ObjectEncoder[Jvm] = deriveEncoder
  implicit val testFrameworkConfigEncoder: ObjectEncoder[TestFramework] = deriveEncoder
  implicit val testArgumentConfigEncoder: ObjectEncoder[TestArgument] = deriveEncoder
  implicit val testOptionsConfigEncoder: ObjectEncoder[TestOptions] = deriveEncoder
  implicit val testConfigEncoder: ObjectEncoder[Test] = deriveEncoder
  implicit val classpathOptionsEncoder: ObjectEncoder[ClasspathOptions] = deriveEncoder
  implicit val scalaConfigEncoder: ObjectEncoder[Scala] = deriveEncoder
  implicit val projectConfigEncoder: ObjectEncoder[Project] = deriveEncoder
  implicit val allConfigEncoder: ObjectEncoder[All] = deriveEncoder
}
