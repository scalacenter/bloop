package sbt.internal.inc.bloop.internal

import java.io.File

import _root_.bloop.io.ByteHasher

import xsbti.compile.FileHash
import sbt.internal.inc.Hash
import sbt.internal.inc.Stamps
import sbt.internal.inc.Stamper
import sbt.internal.inc.EmptyStamp
import xsbti.compile.analysis.ReadStamps

object BloopStamps {
  def initial: ReadStamps = {
    Stamps.initial(
      Stamper.forLastModified,
      // The hash is for the sources
      BloopStamps.forHash,
      Stamper.forLastModified
    )
  }

  private final val emptyHash = EmptyStamp.hashCode
  def emptyHash(file: File): FileHash = FileHash.of(file, emptyHash)

  def uniquePlaceholderHash: FileHash = {
    val id = "i am a dummy placeholder"
    FileHash.of(new java.io.File(id), id.hashCode)
  }

  def forHash(file: File): Hash = {
    fromBloopHashToZincHash(ByteHasher.hashFileContents(file))
  }

  def fromBloopHashToZincHash(hash: Int): Hash = {
    val hex = hash.toString
    Hash.unsafeFromString(
      // The resulting hex representation has to have even size
      if (hex.length % 2 == 0) hex
      else "0" + hash
    )
  }
}
