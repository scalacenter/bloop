package bloop.data

import java.nio.file.attribute.FileTime

import bloop.io.AbsolutePath
import bloop.io.Paths.AttributedPath
import bloop.util.CacheHashCode

case class Origin(path: AbsolutePath, lastModifiedtime: FileTime, size: Long, hash: Int)
    extends CacheHashCode {
  def toAttributedPath: AttributedPath = AttributedPath(path, lastModifiedtime, size)
}

object Origin {
  def apply(path: AttributedPath, hash: Int): Origin = {
    Origin(path.path, path.lastModifiedTime, path.size, hash)
  }
}
