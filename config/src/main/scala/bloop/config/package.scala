package bloop

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import bloop.config.Config.File
import _root_.io.circe.Printer

package object config {
  def toStr(all: File): String = {
    val f = ConfigEncoderDecoders.allEncoder(all)
    Printer.spaces4.copy(dropNullValues = true).pretty(f)
  }

  def write(all: File, target: Path): Unit = {
    Files.write(target, toStr(all).getBytes(StandardCharsets.UTF_8))
    ()
  }
}
