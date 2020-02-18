package bloop.io

import java.io.File
import java.net.URI
import java.nio.file.{Path, Paths => NioPaths}

final class RelativePath private (val underlying: Path) extends AnyVal {
  def syntax: String = toString
  def structure: String = s"""RelativePath("$syntax")"""
  override def toString: String = underlying.toString

  def toFile: File = underlying.toFile()
  def toUri(isDirectory: Boolean): URI = {
    val suffix = if (isDirectory) "/" else ""
    // Can't use toNIO.toUri because it produces an absolute URI.
    import scala.collection.JavaConverters._
    val names = underlying.iterator().asScala
    val uris = names.map { name =>
      // URI encode each part of the path individually.
      new URI(null, null, name.toString, null)
    }
    URI.create(uris.mkString("", "/", suffix))
  }
  def toAbsolute(root: AbsolutePath): AbsolutePath = root.resolve(this)
  def relativize(other: RelativePath): RelativePath =
    RelativePath(underlying.relativize(other.underlying))
  def getParent: RelativePath = RelativePath(underlying.getParent)

  def resolve(other: Path): RelativePath = RelativePath(underlying.resolve(other))
  def resolveRelative(other: RelativePath): RelativePath = resolve(other.underlying)
  def resolve(path: String): RelativePath = resolve(NioPaths.get(path))
  def resolveSibling(f: String => String): RelativePath =
    RelativePath(underlying.resolveSibling(f(underlying.getFileName.toString)))
}

object RelativePath {
  def apply(path: String): RelativePath = RelativePath(NioPaths.get(path))
  def apply(file: File): RelativePath = RelativePath(file.toPath)
  def apply(path: Path): RelativePath =
    if (!path.isAbsolute) new RelativePath(path)
    else throw new RuntimeException(s"$path is not relative")
}
