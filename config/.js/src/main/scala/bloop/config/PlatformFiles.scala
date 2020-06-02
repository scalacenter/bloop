package bloop.config

object PlatformFiles {
  type Path = String
  val emptyPath: Path = getPath("")
  def getPath(path: String): Path = path

  def userDir: Path = NodePath.resolve(".")

  def createTempFile(prefix: String, suffix: String): Path = {
    val tmpDir = NodeFS.mkdtempSync(prefix, "utf8")
    val path = NodePath.join(tmpDir, prefix + suffix)
    NodeFS.closeSync(NodeFS.openSync(path, "w"))
    path
  }

  def deleteTempFile(path: Path): Unit = {
    NodeFS.rmdirSync(NodePath.dirname(path), new NodeFS.RmDirOptions {
      override val recursive = true
    })
  }

  def resolve(parent: Path, child: String): Path =
    NodePath.resolve(parent, child)

  def readAllBytes(path: Path): Array[Byte] =
    NodeFS.readFileSync(path, "utf8").getBytes()

  def write(path: Path, bytes: Array[Byte]): Unit =
    NodeFS.writeFileSync(path, bytes.map(_.toChar).mkString)
}
