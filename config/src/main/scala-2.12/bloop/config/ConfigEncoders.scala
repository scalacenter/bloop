package bloop.config

import java.nio.file.Path

import bloop.config.Config._
import io.circe.derivation.deriveEncoder
import io.circe.{Json, ObjectEncoder, RootEncoder}

object ConfigEncoders {
  implicit val pathEncoder: RootEncoder[Path] = new RootEncoder[Path] {
    override final def apply(a: Path): Json = Json.fromString(a.toString)
  }

  implicit val compileOrderEncoder: RootEncoder[CompileOrder] = new RootEncoder[CompileOrder] {
    override final def apply(o: CompileOrder): Json = o match {
      case Mixed => Json.fromString(Mixed.id)
      case JavaThenScala => Json.fromString(JavaThenScala.id)
      case ScalaThenJava => Json.fromString(ScalaThenJava.id)
    }
  }

  implicit val platformConfigEncoder: RootEncoder[Platform] = new RootEncoder[Platform] {
    override final def apply(platform: Platform): Json = Json.fromString(platform.name)
  }

  implicit val javaConfigEncoder: ObjectEncoder[Java] = deriveEncoder
  implicit val jvmConfigEncoder: ObjectEncoder[Jvm] = deriveEncoder
  implicit val testFrameworkConfigEncoder: ObjectEncoder[TestFramework] = deriveEncoder
  implicit val testArgumentConfigEncoder: ObjectEncoder[TestArgument] = deriveEncoder
  implicit val testOptionsConfigEncoder: ObjectEncoder[TestOptions] = deriveEncoder
  implicit val testConfigEncoder: ObjectEncoder[Test] = deriveEncoder
  implicit val classpathOptionsEncoder: ObjectEncoder[ClasspathOptions] = deriveEncoder
  implicit val compileOptionsEncoder: ObjectEncoder[CompileOptions] = deriveEncoder
  implicit val scalaConfigEncoder: ObjectEncoder[Scala] = deriveEncoder
  implicit val projectConfigEncoder: ObjectEncoder[Project] = deriveEncoder
  implicit val allConfigEncoder: ObjectEncoder[File] = deriveEncoder
}
