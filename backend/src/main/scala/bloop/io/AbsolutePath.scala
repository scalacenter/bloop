// Slight modification of scalameta io utils
package bloop.io

import java.io.File
import java.net.URI
import java.nio.file.{FileSystems, Files, Path, PathMatcher, Paths => NioPaths}

final class AbsolutePath private (val underlying: Path) extends AnyVal {
  def syntax: String = toString
  def structure: String = s"""AbsolutePath("$syntax")"""
  override def toString: String = underlying.toString

  def toRelative(prefix: AbsolutePath): RelativePath =
    RelativePath(prefix.underlying.relativize(underlying))

  def resolve(other: RelativePath): AbsolutePath =
    AbsolutePath(underlying.resolve(other.underlying))(this)
  def resolve(other: String): AbsolutePath = AbsolutePath(underlying.resolve(other))(this)
  def getParent: AbsolutePath = AbsolutePath(underlying.getParent)

  def exists: Boolean = Files.exists(underlying)
  def isFile: Boolean = Files.isRegularFile(underlying)
  def isDirectory: Boolean = Files.isDirectory(underlying)
  def readAllBytes: Array[Byte] = Files.readAllBytes(underlying)
  def toFile: File = underlying.toFile
  def toBspUri: URI = underlying.toUri
  def toBspSourceUri: URI = {
    val uri = toBspUri
    if (exists) uri
    else {
      if (AbsolutePath.sourceFileNameMatcher.matches(underlying.getFileName)) uri
      // If path doesn't exist and its name doesn't look like a file, assume it's a dir
      else new java.net.URI(uri.toString + "/")
    }
  }
}

object AbsolutePath {
  implicit def workingDirectory: AbsolutePath =
    new AbsolutePath(NioPaths.get(sys.props("user.dir")))
  def apply(uri: URI): AbsolutePath = apply(NioPaths.get(uri))
  def apply(file: File)(implicit cwd: AbsolutePath): AbsolutePath = apply(file.toPath)(cwd)
  def apply(path: String)(implicit cwd: AbsolutePath): AbsolutePath = apply(NioPaths.get(path))(cwd)
  def apply(path: Path)(implicit cwd: AbsolutePath): AbsolutePath =
    if (path.isAbsolute) new AbsolutePath(path) else cwd.resolve(path.toString)

  // Necessary to test wrong paths in tests...
  private[bloop] def completelyUnsafe(path: String): AbsolutePath =
    new AbsolutePath(NioPaths.get(path))

  private[io] val sourceFileNameMatcher: PathMatcher =
    FileSystems.getDefault.getPathMatcher("glob:*.{scala, java}")
}
