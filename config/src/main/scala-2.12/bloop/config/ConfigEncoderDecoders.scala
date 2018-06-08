package bloop.config

import java.nio.file.{Path, Paths}

import bloop.config.Config._
import io.circe.{CursorOp, Decoder, DecodingFailure, HCursor, Json, ObjectEncoder, RootEncoder}
import io.circe.Decoder.Result

import scala.util.Try

object ConfigEncoderDecoders {
  import io.circe.derivation._

  implicit val pathDecoder: Decoder[Path] = Decoder.decodeString.emapTry(s => Try(Paths.get(s)))
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

  implicit val compileOrderDecoder: Decoder[CompileOrder] = new Decoder[CompileOrder] {
    override def apply(c: HCursor): Result[CompileOrder] = {
      c.as[String].flatMap {
        case Mixed.id => Right(Mixed)
        case JavaThenScala.id => Right(JavaThenScala)
        case ScalaThenJava.id => Right(ScalaThenJava)
        case _ =>
          val msg = s"Expected compile order ${CompileOrder.All.map(s => s"'$s'").mkString(", ")})."
          Left(DecodingFailure(msg, List(CursorOp.DownField("id"))))
      }
    }
  }

  implicit val platformEncoder: RootEncoder[Platform] = new RootEncoder[Platform] {
    override final def apply(platform: Platform): Json = Json.fromString(platform.name)
  }

  implicit val platformDecoder: Decoder[Platform] = new Decoder[Platform] {
    override def apply(c: HCursor): Result[Platform] = {
      c.as[String].flatMap {
        case Platform.JVM.name => Right(Platform.JVM)
        case Platform.JS.name => Right(Platform.JS)
        case Platform.Native.name => Right(Platform.Native)
        case _ =>
          val msg = s"Expected platform ${Platform.All.map(s => s"'$s'").mkString(", ")})."
          Left(DecodingFailure(msg, c.history))
      }
    }
  }

  implicit val nativeEncoder: ObjectEncoder[NativeConfig] = deriveEncoder
  implicit val nativeDecoder: Decoder[NativeConfig] = deriveDecoder

  implicit val jsEncoder: ObjectEncoder[JsConfig] = deriveEncoder
  implicit val jsDecoder: Decoder[JsConfig] = deriveDecoder

  implicit val checksumEncoder: ObjectEncoder[Checksum] = deriveEncoder
  implicit val checksumDecoder: Decoder[Checksum] = deriveDecoder

  implicit val moduleEncoder: ObjectEncoder[Module] = deriveEncoder
  implicit val moduleDecoder: Decoder[Module] = deriveDecoder

  implicit val artifactEncoder: ObjectEncoder[Artifact] = deriveEncoder
  implicit val artifactDecoder: Decoder[Artifact] = deriveDecoder

  implicit val resolutionEncoder: ObjectEncoder[Resolution] = deriveEncoder
  implicit val resolutionDecoder: Decoder[Resolution] = deriveDecoder

  implicit val javaEncoder: ObjectEncoder[Java] = deriveEncoder
  implicit val javaDecoder: Decoder[Java] = deriveDecoder

  implicit val jvmEncoder: ObjectEncoder[Jvm] = deriveEncoder
  implicit val jvmDecoder: Decoder[Jvm] = deriveDecoder

  implicit val testFrameworkEncoder: ObjectEncoder[TestFramework] = deriveEncoder
  implicit val testFrameworkDecoder: Decoder[TestFramework] = deriveDecoder

  implicit val testArgumentEncoder: ObjectEncoder[TestArgument] = deriveEncoder
  implicit val testArgumentDecoder: Decoder[TestArgument] = deriveDecoder

  implicit val testOptionsEncoder: ObjectEncoder[TestOptions] = deriveEncoder
  implicit val testOptionsDecoder: Decoder[TestOptions] = deriveDecoder

  implicit val testEncoder: ObjectEncoder[Test] = deriveEncoder
  implicit val testDecoder: Decoder[Test] = deriveDecoder

  implicit val classpathOptionsEncoder: ObjectEncoder[ClasspathOptions] = deriveEncoder
  implicit val classpathOptionsDecoder: Decoder[ClasspathOptions] = deriveDecoder

  implicit val compileOptionsEncoder: ObjectEncoder[CompileOptions] = deriveEncoder
  implicit val compileOptionsDecoder: Decoder[CompileOptions] = deriveDecoder

  implicit val scalaEncoder: ObjectEncoder[Scala] = deriveEncoder
  implicit val scalaDecoder: Decoder[Scala] = deriveDecoder

  implicit val projectEncoder: ObjectEncoder[Project] = deriveEncoder
  implicit val projectDecoder: Decoder[Project] = deriveDecoder

  implicit val allEncoder: ObjectEncoder[File] = deriveEncoder
  implicit val allDecoder: Decoder[File] = deriveDecoder
}
