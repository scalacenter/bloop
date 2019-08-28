package bloop.config

import scala.util.Try

import java.nio.file.{Path, Paths, Files}
import java.nio.charset.StandardCharsets

object JsoniterEncoderDecoders {
  import com.github.plokhotnyuk.jsoniter_scala.macros._
  import com.github.plokhotnyuk.jsoniter_scala.core._

  implicit val codecPath: JsonValueCodec[Path] = new JsonValueCodec[Path] {
    val nullValue: Path = Paths.get("")
    def encodeValue(x: Path, out: JsonWriter): Unit = out.writeVal(x.toAbsolutePath().toString)
    def decodeValue(in: JsonReader, default: Path): Path = {
      if (in.isNextToken('"')) {
        in.rollbackToken()
        Try(Paths.get(in.readString(""))).toOption.getOrElse(nullValue)
      } else {
        in.rollbackToken()
        nullValue
      }
    }
  }

  implicit val codecCompileOrder: JsonValueCodec[Config.CompileOrder] = {
    new JsonValueCodec[Config.CompileOrder] {
      val nullValue: Config.CompileOrder = null.asInstanceOf[Config.CompileOrder]
      def encodeValue(x: Config.CompileOrder, out: JsonWriter): Unit = {
        x match {
          case Config.Mixed => out.writeVal(Config.Mixed.id)
          case Config.JavaThenScala => out.writeVal(Config.JavaThenScala.id)
          case Config.ScalaThenJava => out.writeVal(Config.ScalaThenJava.id)
        }
      }

      def decodeValue(in: JsonReader, default: Config.CompileOrder): Config.CompileOrder = {
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.Mixed.id => Config.Mixed
            case Config.JavaThenScala.id => Config.JavaThenScala
            case Config.ScalaThenJava.id => Config.ScalaThenJava
            case _ =>
              val supported = Config.CompileOrder.All.map(s => s"'$s'").mkString(", ")
              throw new IllegalArgumentException(s"Expected compile order ${supported})")
          }
        } else {
          in.rollbackToken()
          nullValue
        }
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
      def decodeValue(in: JsonReader, default: Config.LinkerMode): Config.LinkerMode = {
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.LinkerMode.Debug.id => Config.LinkerMode.Debug
            case Config.LinkerMode.Release.id => Config.LinkerMode.Release
            case _ =>
              throw new IllegalArgumentException(
                s"Expected linker mode ${Config.LinkerMode.All.map(s => s"'$s'").mkString(", ")})"
              )
          }
        } else {
          in.rollbackToken()
          nullValue
        }
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
      def decodeValue(in: JsonReader, default: Config.ModuleKindJS): Config.ModuleKindJS = {
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.ModuleKindJS.CommonJSModule.id => Config.ModuleKindJS.CommonJSModule
            case Config.ModuleKindJS.NoModule.id => Config.ModuleKindJS.NoModule
            case _ =>
              throw new IllegalArgumentException(
                s"Expected linker mode ${Config.ModuleKindJS.All.map(s => s"'$s'").mkString(", ")})"
              )
          }
        } else {
          in.rollbackToken()
          nullValue
        }
      }
    }
  }

  implicit val codecJvmConfig: JsonValueCodec[JvmConfig] =
    JsonCodecMaker.make[JvmConfig](CodecMakerConfig())

  implicit val codecJsConfig: JsonValueCodec[JsConfig] =
    JsonCodecMaker.make[JsConfig](CodecMakerConfig())

  implicit val codecNativeConfig: NativeonValueCodec[NativeConfig] =
    NativeonCodecMaker.make[NativeConfig](CodecMakerConfig())

  case class JvmPlatform(name: String, config: Config.JvmConfig, mainClass: Option[String])
  implicit val codecJvmPlatform: JsonValueCodec[JvmPlatform] =
    JsonCodecMaker.make[JvmPlatform](CodecMakerConfig())

  case class JsPlatform(name: String, config: Config.JsConfig, mainClass: Option[String])
  implicit val codecJsPlatform: JsonValueCodec[JsPlatform] =
    JsonCodecMaker.make[JsPlatform](CodecMakerConfig())

  case class NativePlatform(name: String, config: Config.NativeConfig, mainClass: Option[String])
  implicit val codecNativePlatform: JsonValueCodec[NativePlatform] =
    JsonCodecMaker.make[NativePlatform](CodecMakerConfig())

  implicit val codecPlatform: JsonValueCodec[Config.Platform] = {
    new JsonValueCodec[Config.Platform] {
      private final val N = "name"
      private final val C = "config"
      private final val M = "mainClass"
      val nullValue: Config.Platform = null.asInstanceOf[Config.Platform]
      def encodeValue(x: Config.Platform, out: JsonWriter): Unit = {
        x match {
          case Config.Platform.Jvm(config, mainClass) =>
            val platform = JvmPlatform(Config.Platform.Jvm.name, config, mainClass)
            codecJvmPlatform.encodeValue(platform, out)
          case Config.Platform.Js(config, mainClass) =>
            val platform = JsPlatform(Config.Platform.Js.name, config, mainClass)
            codecJsPlatform.encodeValue(platform, out)
          case Config.Platform.Native(config, mainClass) =>
            val platform = NativePlatform(Config.Platform.Native.name, config, mainClass)
            codecNativePlatform.encodeValue(platform, out)
        }
      }
      def decodeValue(in: JsonReader, default: Config.Platform): Config.Platform = {
        if (in.isNextToken('{')) {
          if (!in.isNextToken('}')) {
            in.rollbackToken()
            def readConfigKeyValue: Unit = {

            in.readKeyAsString() match {
              case "" => platformConfig = in.readString(null)
              case C => config = in.readString()
              case M =>
            }
            }
            /*
                name match {
                  case Config.Platform.Jvm.name => Config.Platform.Jvm
                  case Config.Platform.Js.name => Config.Platform.Js
                  case Config.Platform.Native.name => Config.Platform.Native
                  case name => in.decodeError(s"name $name cannot be decoded")
                }
                */

            if (!in.isCurrentToken('}')) in.objectEndOrCommaError()
          }
        } else {
          in.readNullOrTokenError(default, '{')
        }
        /*
          in.
        if (in.isNextToken('"')) {
          in.rollbackToken()
          in.readString(null) match {
            case Config.Platform.CommonJSModule.id => Config.Platform.CommonJSModule
            case Config.Platform.NoModule.id => Config.Platform.NoModule
            case _ =>
              throw new IllegalArgumentException(
                s"Expected linker mode ${Config.Platform.All.map(s => s"'$s'").mkString(", ")})"
              )
          }
        } else {
          in.rollbackToken()
          nullValue
        }
       */
      }
    }
  }

  implicit val codecFile: JsonValueCodec[Config.File] =
    JsonCodecMaker.make[Config.File](CodecMakerConfig())

  def read(configDir: Path): Option[Config.File] = {
    val bytes = Files.readAllBytes(configDir)
    Try(readFromArray[Config.File](bytes)).toOption
  }
}
