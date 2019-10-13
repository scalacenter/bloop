package bloop.integrations.sbt

import bloop.config.Config

import scala.util.Try

import java.nio.file.{Path, Paths, Files}
import java.nio.charset.StandardCharsets

object ConfigReader {
  import com.github.plokhotnyuk.jsoniter_scala.macros._
  import com.github.plokhotnyuk.jsoniter_scala.core._

  implicit val codecPath: JsonValueCodec[Path] = new JsonValueCodec[Path] {
    val nullValue: Path = Paths.get("")
    def encodeValue(x: Path, out: JsonWriter): Unit = out.writeVal(x.toString)
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
        ()
      }
      def decodeValue(in: JsonReader, default: Config.CompileOrder): Config.CompileOrder = {
        ???
      }
    }
  }

  implicit val codecLinkerMode: JsonValueCodec[Config.LinkerMode] = {
    new JsonValueCodec[Config.LinkerMode] {
      val nullValue: Config.LinkerMode = null.asInstanceOf[Config.LinkerMode]
      def encodeValue(x: Config.LinkerMode, out: JsonWriter): Unit = ()
      def decodeValue(in: JsonReader, default: Config.LinkerMode): Config.LinkerMode = ???
    }
  }

  implicit val codecModuleKindJS: JsonValueCodec[Config.ModuleKindJS] = {
    new JsonValueCodec[Config.ModuleKindJS] {
      val nullValue: Config.ModuleKindJS = null.asInstanceOf[Config.ModuleKindJS]
      def encodeValue(x: Config.ModuleKindJS, out: JsonWriter): Unit = ()
      def decodeValue(in: JsonReader, default: Config.ModuleKindJS): Config.ModuleKindJS = ???
    }
  }

  implicit val codecPlatform: JsonValueCodec[Config.Platform] = {
    new JsonValueCodec[Config.Platform] {
      val nullValue: Config.Platform = null.asInstanceOf[Config.Platform]
      def encodeValue(x: Config.Platform, out: JsonWriter): Unit = ()
      def decodeValue(in: JsonReader, default: Config.Platform): Config.Platform = ???
    }
  }

  implicit val codecFile: JsonValueCodec[Config.File] =
    JsonCodecMaker.make[Config.File](CodecMakerConfig)

  def read(configDir: Path): Option[Config.File] = {
    val bytes = Files.readAllBytes(configDir)
    Try(readFromArray[Config.File](bytes)).toOption
  }
}
