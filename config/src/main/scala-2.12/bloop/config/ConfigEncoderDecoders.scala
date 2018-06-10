package bloop.config

import java.nio.file.{Path, Paths}

import bloop.config.Config._
import io.circe.{CursorOp, Decoder, DecodingFailure, HCursor, Json, ObjectEncoder, RootEncoder}
import io.circe.Decoder.Result
import io.circe.derivation._

import scala.util.Try

object ConfigEncoderDecoders {

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

  import LinkerMode.{Debug, Release}
  implicit val linkerModeEncoder: RootEncoder[LinkerMode] = new RootEncoder[LinkerMode] {
    override final def apply(o: LinkerMode): Json = o match {
      case Debug => Json.fromString(Debug.id)
      case Release => Json.fromString(Release.id)
    }
  }

  implicit val linkerModeDecoder: Decoder[LinkerMode] = new Decoder[LinkerMode] {
    override def apply(c: HCursor): Result[LinkerMode] = {
      c.as[String].flatMap {
        case Debug.id => Right(Debug)
        case Release.id => Right(Release)
        case _ =>
          val msg = s"Expected linker mode ${LinkerMode.All.map(s => s"'$s'").mkString(", ")})."
          Left(DecodingFailure(msg, c.history))
      }
    }
  }

  implicit val jvmEncoder: ObjectEncoder[JvmConfig] = deriveEncoder
  implicit val jvmDecoder: Decoder[JvmConfig] = deriveDecoder

  implicit val nativeOptionsEncoder: ObjectEncoder[NativeOptions] = deriveEncoder
  implicit val nativeOptionsDecoder: Decoder[NativeOptions] = deriveDecoder

  implicit val nativeEncoder: ObjectEncoder[NativeConfig] = deriveEncoder
  implicit val nativeDecoder: Decoder[NativeConfig] = deriveDecoder

  implicit val jsEncoder: ObjectEncoder[JsConfig] = deriveEncoder
  implicit val jsDecoder: Decoder[JsConfig] = deriveDecoder

  private final val N = "name"
  private final val C = "config"
  implicit val platformEncoder: RootEncoder[Platform] = new RootEncoder[Platform] {
    override final def apply(platform: Platform): Json = platform match {
      case Platform.Jvm(config) =>
        val configJson = Json.fromJsonObject(jvmEncoder.encodeObject(config))
        Json.fromFields(List((N, Json.fromString(Platform.Jvm.name)), (C, configJson)))
      case Platform.Js(config) =>
        val configJson = Json.fromJsonObject(jsEncoder.encodeObject(config))
        Json.fromFields(List((N, Json.fromString(Platform.Js.name)), (C, configJson)))
      case Platform.Native(config) =>
        val configJson = Json.fromJsonObject(nativeEncoder.encodeObject(config))
        Json.fromFields(List((N, Json.fromString(Platform.Native.name)), (C, configJson)))
    }
  }

  implicit val platformDecoder: Decoder[Platform] = new Decoder[Platform] {
    private final val C = "config"
    override def apply(c: HCursor): Result[Platform] = {
      c.downField(N).as[String].flatMap {
        case Platform.Jvm.name => c.downField(C).as[JvmConfig].map(c => Platform.Jvm(c))
        case Platform.Js.name => c.downField(C).as[JsConfig].map(c => Platform.Js(c))
        case Platform.Native.name => c.downField(C).as[NativeConfig].map(c => Platform.Native(c))
        case _ =>
          val msg = s"Expected platform ${Platform.All.map(s => s"'$s'").mkString(", ")})."
          Left(DecodingFailure(msg, c.history))
      }
    }
  }

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

  implicit val testFrameworkEncoder: ObjectEncoder[TestFramework] = deriveEncoder
  implicit val testFrameworkDecoder: Decoder[TestFramework] = deriveDecoder

  implicit val testArgumentEncoder: ObjectEncoder[TestArgument] = deriveEncoder
  implicit val testArgumentDecoder: Decoder[TestArgument] = deriveDecoder

  implicit val testOptionsEncoder: ObjectEncoder[TestOptions] = deriveEncoder
  implicit val testOptionsDecoder: Decoder[TestOptions] = deriveDecoder

  implicit val testEncoder: ObjectEncoder[Test] = deriveEncoder
  implicit val testDecoder: Decoder[Test] = deriveDecoder

  implicit val compileOptionsEncoder: ObjectEncoder[CompileSetup] = deriveEncoder
  implicit val compileOptionsDecoder: Decoder[CompileSetup] = deriveDecoder

  implicit val scalaEncoder: ObjectEncoder[Scala] = deriveEncoder
  implicit val scalaDecoder: Decoder[Scala] = deriveDecoder

  implicit val projectEncoder: ObjectEncoder[Project] = deriveEncoder
  implicit val projectDecoder: Decoder[Project] = deriveDecoder

  implicit val allEncoder: ObjectEncoder[File] = deriveEncoder
  implicit val allDecoder: Decoder[File] = deriveDecoder
}
