package bloop.config

import java.nio.file.{Files, Paths}

object PlatformFiles {
  type Path = java.nio.file.Path
  val emptyPath: Path = Paths.get("")
  def getPath(path: String): Path = Paths.get(path)

  def userDir: Path = getPath(System.getProperty("user.dir"))

  def createTempFile(prefix: String, suffix: String): Path =
    Files.createTempFile(prefix, suffix)

  def deleteTempFile(path: Path): Unit =
    Files.delete(path)

  def resolve(parent: Path, child: String): Path =
    parent.resolve(child)

  def readAllBytes(path: Path): Array[Byte] =
    Files.readAllBytes(path)

  def write(path: Path, bytes: Array[Byte]): Unit = {
    Files.write(path, bytes)
    ()
  }
}
