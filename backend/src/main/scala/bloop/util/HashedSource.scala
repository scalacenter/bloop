package bloop.util

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Path

import bloop.reporter.Problem

import sbt.internal.inc.HashUtil
import sbt.internal.inc.PlainVirtualFileConverter
import xsbti.BasicVirtualFileRef
import xsbti.FileConverter
import xsbti.PathBasedFile
import xsbti.VirtualFile
import xsbti.VirtualFileRef

/**
 * In memory VirtualFile that has a hash corresponding to the actual file
 * being given to scala and java compielrs.
 *
 * @param content the content of the file
 * @param bloopHash the hash of the file based on the input
 * @param path path to the file
 */
class HashedSource(content: Array[Byte], val bloopHash: Int, path: Path)
    extends BasicVirtualFileRef(path.toString)
    with VirtualFile
    with PathBasedFile {

  override def contentHash(): Long = HashUtil.farmHash(content)

  override def name(): String = path.getFileName.toString

  override def toPath(): Path = path

  override def input(): InputStream = {
    new ByteArrayInputStream(content)
  }
  override def toString: String = s"VirtualSourceFile($Problem.id@${bloopHash})"
}

object HashedSource {
  private val baseConverter = PlainVirtualFileConverter.converter
  class HashedSourceConverter extends FileConverter {
    override def toPath(file: VirtualFileRef): Path = baseConverter.toPath(file)
    override def toVirtualFile(path: Path): VirtualFile = baseConverter.toVirtualFile(path)
    override def toVirtualFile(ref: VirtualFileRef): VirtualFile = {
      ref match {
        case virtualFile: VirtualFile => virtualFile
        case other => baseConverter.toVirtualFile(other)
      }
    }
  }
  val converter: FileConverter = new HashedSourceConverter()
}
