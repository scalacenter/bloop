package bloop.config

import java.nio.file.{Files, Paths}

object TargetPlatform {
  type Path = java.nio.file.Path
  val emptyPath: Path = Paths.get("")
  def getPath(path: String): Path = Paths.get(path)

  def userDir: Path = getPath(System.getProperty("user.dir"))

  def createTempFile(prefix: String, suffix: String): Path = {
    val path = Files.createTempFile(prefix, suffix)
    path.toFile.deleteOnExit()
    path
  }

  def resolve(parent: Path, child: String): Path =
    parent.resolve(child)

  def readAllBytes(path: Path): Array[Byte] =
    Files.readAllBytes(path)

  def write(path: Path, bytes: Array[Byte]): Unit = {
    Files.write(path, bytes)
    ()
  }
}
