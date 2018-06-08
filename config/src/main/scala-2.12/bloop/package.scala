package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import bloop.config.Config.File

package object config {
  def toStr(all: File): String = ConfigEncoderDecoders.allEncoder(all).spaces4
  def write(all: File, target: Path): Unit = {
    Files.write(target, toStr(all).getBytes(StandardCharsets.UTF_8))
    ()
  }
}
