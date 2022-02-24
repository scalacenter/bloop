package sbt.internal.inc.bloop.internal

import java.io.File

import _root_.bloop.io.ByteHasher

import xsbti.compile.FileHash
import sbt.internal.inc.Hash
import sbt.internal.inc.Stamps
import sbt.internal.inc.Stamper
import sbt.internal.inc.EmptyStamp
import xsbti.compile.analysis.{ReadStamps, Stamp}
import xsbti.VirtualFileRef
import bloop.util.AnalysisUtils
import java.nio.file.Path
import sbt.internal.inc.PlainVirtualFileConverter

object BloopStamps {
  private val converter = PlainVirtualFileConverter.converter
  def initial: ReadStamps = {
    Stamps.initial(
      Stamper.forLastModifiedInRootPaths(converter),
      // The hash is for the sources
      BloopStamps.forHash,
      Stamper.forHashInRootPaths(converter)
    )
  }

  private final val emptyHash = scala.util.Random.nextInt()
  private final val directoryHash = scala.util.Random.nextInt()
  final val cancelledHash = scala.util.Random.nextInt()

  def emptyHash(path: Path): FileHash = FileHash.of(path, emptyHash)
  def cancelledHash(path: Path): FileHash = FileHash.of(path, cancelledHash)

  def directoryHash(path: Path): FileHash = FileHash.of(path, directoryHash)
  def isDirectoryHash(fh: FileHash): Boolean = fh.hash == directoryHash

  def forHash(file: VirtualFileRef): Hash = {
    fromBloopHashToZincHash(ByteHasher.hashFileContents(converter.toPath(file).toFile()))
  }

  def emptyStamps: Stamp = EmptyStamp

  def fromBloopHashToZincHash(hash: Int): Hash = {
    val hex = hash.toString
    Hash.unsafeFromString(
      // The resulting hex representation has to have even size
      if (hex.length % 2 == 0) hex
      else "0" + hash
    )
  }
}
