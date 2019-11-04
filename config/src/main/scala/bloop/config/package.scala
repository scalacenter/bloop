package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.util.Try
import bloop.config.Config.File

package object config {
  def write(all: File): String = ConfigCodecs.toStr(all)
  def write(all: File, target: Path): Unit = {
    Files.write(target, write(all).getBytes(StandardCharsets.UTF_8))
    ()
  }

  def read(bytes: Array[Byte]): Either[Throwable, Config.File] = ConfigCodecs.read(bytes)
  def read(jsonConfig: Path): Either[Throwable, Config.File] = {
    ConfigCodecs.read(jsonConfig)
  }
}
