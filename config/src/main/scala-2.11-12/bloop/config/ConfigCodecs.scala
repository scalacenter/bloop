package bloop.config

import java.nio.file.{Files, Path, Paths}

import com.github.plokhotnyuk.jsoniter_scala.macros._
import com.github.plokhotnyuk.jsoniter_scala.core._

import scala.util.Try

object ConfigCodecs {

  implicit val codecPath: JsonValueCodec[Path] = new JsonValueCodec[Path] {
    val nullValue: Path = Paths.get("")
    def encodeValue(x: Path, out: JsonWriter): Unit = out.writeVal(x.toString)
    def decodeValue(in: JsonReader, default: Path): Path =
      if (in.isNextToken('"')) {
        in.rollbackToken()
        Try(Paths.get(in.readString(""))).toOption.getOrElse(nullValue)
      } else {
        in.rollbackToken()
        nullValue
      }
  }

  implicit val codecCompileOrder: JsonValueCodec[Config.CompileOrder] = {
    new JsonValueCodec[Config.CompileOrder] {
      val nullValue: Config.CompileOrder = null.asInstanceOf[Config.CompileOrder]
      def encodeValue(x: Config.CompileOrder, out: JsonWriter): Unit =
        x match {
          case Config.Mixed => out.writeVal(Config.Mixed.id)
          case Config.JavaThenScala => out.writeVal(Config.JavaThenScala.id)
          case Config.ScalaThenJava => out.writeVal(Config.ScalaThenJava.id)
        }

      def decodeValue(in: JsonReader, default: Config.CompileOrder): Config.CompileOrder =
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.Mixed.id => Config.Mixed
            case Config.JavaThenScala.id => Config.JavaThenScala
            case Config.ScalaThenJava.id => Config.ScalaThenJava
            case _ => in.decodeError(s"Expected compile order ${Config.CompileOrder.All.mkString("'", "', '", "'")}")
          }
        } else {
          in.rollbackToken()
          nullValue
        }
    }
  }

  implicit val codecLinkerMode: JsonValueCodec[Config.LinkerMode] = {
    new JsonValueCodec[Config.LinkerMode] {
      val nullValue: Config.LinkerMode = null.asInstanceOf[Config.LinkerMode]
      def encodeValue(x: Config.LinkerMode, out: JsonWriter): Unit = {
        val str = x match {
          case Config.LinkerMode.Debug => Config.LinkerMode.Debug.id
          case Config.LinkerMode.Release => Config.LinkerMode.Release.id
        }
        out.writeVal(str)
      }
      def decodeValue(in: JsonReader, default: Config.LinkerMode): Config.LinkerMode =
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.LinkerMode.Debug.id => Config.LinkerMode.Debug
            case Config.LinkerMode.Release.id => Config.LinkerMode.Release
            case _ => in.decodeError(s"Expected linker mode ${Config.LinkerMode.All.mkString("'", "', '", "'")}")
          }
        } else {
          in.rollbackToken()
          nullValue
        }
    }
  }

  implicit val codecModuleKindJS: JsonValueCodec[Config.ModuleKindJS] = {
    new JsonValueCodec[Config.ModuleKindJS] {
      val nullValue: Config.ModuleKindJS = null.asInstanceOf[Config.ModuleKindJS]
      def encodeValue(x: Config.ModuleKindJS, out: JsonWriter): Unit = {
        val str = x match {
          case Config.ModuleKindJS.CommonJSModule => Config.ModuleKindJS.CommonJSModule.id
          case Config.ModuleKindJS.NoModule => Config.ModuleKindJS.NoModule.id
        }
        out.writeVal(str)
      }
      def decodeValue(in: JsonReader, default: Config.ModuleKindJS): Config.ModuleKindJS =
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.ModuleKindJS.CommonJSModule.id => Config.ModuleKindJS.CommonJSModule
            case Config.ModuleKindJS.NoModule.id => Config.ModuleKindJS.NoModule
            case _ => in.decodeError(s"Expected linker mode ${Config.ModuleKindJS.All.mkString("'", "', '", "'")}")
          }
        } else {
          in.rollbackToken()
          nullValue
        }
    }
  }

  implicit val codecJvmConfig: JsonValueCodec[Config.JvmConfig] =
    JsonCodecMaker.make[Config.JvmConfig](CodecMakerConfig)

  implicit val codecJsConfig: JsonValueCodec[Config.JsConfig] =
    JsonCodecMaker.make[Config.JsConfig](CodecMakerConfig)

  implicit val codecNativeConfig: JsonValueCodec[Config.NativeConfig] =
    JsonCodecMaker.make[Config.NativeConfig](CodecMakerConfig)

  sealed trait platform
  case class jvm(config: Config.JvmConfig, mainClass: Option[String]) extends platform
  case class js(config: Config.JsConfig, mainClass: Option[String]) extends platform
  case class native(config: Config.NativeConfig, mainClass: Option[String]) extends platform

  implicit val codecPlatform: JsonValueCodec[Config.Platform] = new JsonValueCodec[Config.Platform] {
    val codec: JsonValueCodec[platform] = JsonCodecMaker.make[platform](CodecMakerConfig.withDiscriminatorFieldName(Some("name")))
    val nullValue: Config.Platform = null.asInstanceOf[Config.Platform]
    def encodeValue(x: Config.Platform, out: JsonWriter): Unit =
      codec.encodeValue(x match {
        case Config.Platform.Jvm(config, mainClass) => jvm(config, mainClass)
        case Config.Platform.Js(config, mainClass) => js(config, mainClass)
        case Config.Platform.Native(config, mainClass) => native(config, mainClass)
      }, out)
    def decodeValue(in: JsonReader, default: Config.Platform): Config.Platform =
      codec.decodeValue(in, null) match {
        case jvm(config, mainClass) => Config.Platform.Jvm(config, mainClass)
        case js(config, mainClass) => Config.Platform.Js(config, mainClass)
        case native(config, mainClass) => Config.Platform.Native(config, mainClass)
      }
  }

  implicit val codecFile: JsonValueCodec[Config.File] =
    JsonCodecMaker.make[Config.File](CodecMakerConfig)

  def read(configDir: Path): Option[Config.File] = {
    val bytes = Files.readAllBytes(configDir)
    Try(readFromArray[Config.File](bytes)).toOption
  }
}
