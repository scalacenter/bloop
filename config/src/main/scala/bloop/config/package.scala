package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.Try

import io.circe.{Error => CirceError}

import bloop.config.Config.File
import _root_.io.circe.Printer

package object config {
  import scala.util.Failure
  import scala.util.Success
  import io.circe.ParsingFailure
  def toStr(all: File): String = {
    val f = ConfigEncoderDecoders.allEncoder(all)
    Printer.spaces4.copy(dropNullValues = true).pretty(f)
  }

  def write(all: File, target: Path): Unit = {
    Files.write(target, toStr(all).getBytes(StandardCharsets.UTF_8))
    ()
  }

  def read(jsonConfig: Path): Either[Throwable, Config.File] = {
    import io.circe.parser
    import ConfigEncoderDecoders._
    Try(new String(Files.readAllBytes(jsonConfig), StandardCharsets.UTF_8)) match {
      case Failure(exception) => Left(exception)
      case Success(contents) =>
        parser.parse(contents).right.flatMap { parsed =>
          allDecoder.decodeJson(parsed)
        }
    }
  }
}
