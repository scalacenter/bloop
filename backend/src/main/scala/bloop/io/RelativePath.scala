// Slight modification of scalameta io utils
package bloop.io

import java.io.File
import java.nio.file.{Path, Paths}

final class RelativePath private (val underlying: Path) extends AnyVal {
  def syntax: String = toString
  def structure: String = s"""RelativePath("$syntax")"""
  override def toString: String = underlying.toString

  def toAbsolute(root: AbsolutePath): AbsolutePath = root.resolve(this)
  def relativize(other: RelativePath): RelativePath =
    RelativePath(underlying.relativize(other.underlying))

  def resolve(other: Path): RelativePath = RelativePath(underlying.resolve(other))
  def resolveRelative(other: RelativePath): RelativePath = resolve(other.underlying)
  def resolve(path: String): RelativePath = resolve(Paths.get(path))
  def resolveSibling(f: String => String): RelativePath =
    RelativePath(underlying.resolveSibling(f(underlying.getFileName.toString)))
}

object RelativePath {
  def apply(path: String): RelativePath = RelativePath(Paths.get(path))
  def apply(file: File): RelativePath = RelativePath(file.toPath)
  def apply(path: Path): RelativePath =
    if (!path.isAbsolute) new RelativePath(path)
    else throw new RuntimeException(s"$path is not relative")
}
