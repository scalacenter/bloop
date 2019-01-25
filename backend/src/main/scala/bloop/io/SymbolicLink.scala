package bloop.io

import java.io.File
import java.net.URI
import java.nio.file.{FileSystems, Files, Path, PathMatcher, Paths => NioPaths}

final class SymbolicLink private (val underlying: Path) extends AnyVal {
  def dealias: AbsolutePath = AbsolutePath(underlying.toRealPath())
  def syntax: String = toString
  def structure: String = s"""SymbolicLink("$syntax")"""
  override def toString: String = s"${underlying.toString} -> $dealias"

  def exists: Boolean = Files.exists(underlying)
  def dealiasToFile: Boolean = dealias.isFile
  def dealiasToDirectory: Boolean = dealias.isDirectory

  def toFile: File = underlying.toFile
  def toAbsolutePath: AbsolutePath = AbsolutePath(underlying)
}

object SymbolicLink {
  def apply(path: AbsolutePath): Option[SymbolicLink] =
    if (Files.isSymbolicLink(path.underlying)) Some(new SymbolicLink(path.underlying)) else None
}
