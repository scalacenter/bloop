package bloop

import java.nio.charset.StandardCharsets

import bloop.config.Config.File
import bloop.config.TargetPlatform.Path

package object config {
  def write(all: File): String = ConfigCodecs.toStr(all)
  def write(all: File, target: Path): Unit = {
    TargetPlatform.write(target, write(all).getBytes(StandardCharsets.UTF_8))
    ()
  }

  def read(bytes: Array[Byte]): Either[Throwable, Config.File] = ConfigCodecs.read(bytes)
  def read(jsonConfig: Path): Either[Throwable, Config.File] = {
    ConfigCodecs.read(jsonConfig)
  }
}
