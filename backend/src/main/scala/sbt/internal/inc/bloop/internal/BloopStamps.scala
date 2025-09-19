package sbt.internal.inc.bloop.internal

import java.nio.file.Path
import java.{util => ju}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import bloop.UniqueCompileInputs
import bloop.util.HashedSource

import _root_.bloop.io.ByteHasher
import sbt.internal.inc.EmptyStamp
import sbt.internal.inc.FarmHash
import sbt.internal.inc.Hash
import sbt.internal.inc.Stamper
import sbt.internal.inc.Stamps
import sbt.util.Logger
import xsbti.VirtualFile
import xsbti.VirtualFileRef
import xsbti.compile.FileHash
import xsbti.compile.analysis.ReadStamps
import xsbti.compile.analysis.Stamp

/**
 * We make sure that the source stamps used here are the same that we got when
 * reading the sources.
 *
 * @param initial non cached stamps
 * @param uniqueInputs unique inputs that were hashed at the start
 */
class CachedBloopStamps(initial: ReadStamps, uniqueInputs: UniqueCompileInputs) extends ReadStamps {
  private val sourceStamps = uniqueInputs.sources
    .map {
      case kv: HashedSource =>
        kv -> BloopStamps.fromBloopHashToZincHash(kv.bloopHash)
    }
    .map {
      // java map is invariant, which seems to cause a type error if we don't cast to VirtualFileRef and Stamp
      case (vf: VirtualFileRef, hash: Stamp) =>
        vf.asInstanceOf[VirtualFileRef] -> hash.asInstanceOf[Stamp]
    }
    .toMap

  override def getAllLibraryStamps(): ju.Map[VirtualFileRef, Stamp] = initial.getAllLibraryStamps()

  override def getAllSourceStamps(): ju.Map[VirtualFileRef, Stamp] = sourceStamps.asJava

  override def getAllProductStamps(): ju.Map[VirtualFileRef, Stamp] = initial.getAllProductStamps()

  override def source(file: VirtualFile): Stamp =
    sourceStamps.getOrElse(file, BloopStamps.forHash(file))

  override def product(file: VirtualFileRef): Stamp = initial.product(file)
  override def library(file: VirtualFileRef): Stamp = initial.library(file)
}

object BloopStamps {
  private val converter = HashedSource.converter

  private def underlying(logger: Logger) = Stamps.initial(
    BloopStamps.forHash,
    // The hash is for the sources
    BloopStamps.forHash,
    libraryStamp(logger)
  )

  def libraryStamp(logger: Logger): VirtualFileRef => Stamp = { (file: VirtualFileRef) =>
    {
      val baseStamp = Stamper.forHashInRootPaths(converter)
      try {
        baseStamp(file)
      } catch {
        case NonFatal(e) =>
          logger.error(s"Could not calculate hash for ${file.id} because of ${e.getMessage}")
          FarmHash.fromLong(emptyHash.toLong)
      }

    }
  }

  def initial(logger: Logger): ReadStamps =
    Stamps.timeWrapBinaryStamps(underlying(logger), converter)

  private final val emptyHash = scala.util.Random.nextInt()
  private final val directoryHash = scala.util.Random.nextInt()
  final val cancelledHash = scala.util.Random.nextInt()

  def emptyHash(path: Path): FileHash = FileHash.of(path, emptyHash)
  def cancelledHash(path: Path): FileHash = FileHash.of(path, cancelledHash)

  def directoryHash(path: Path): FileHash = FileHash.of(path, directoryHash)
  def isDirectoryHash(fh: FileHash): Boolean = fh.hash == directoryHash

  def forHash(fileRef: VirtualFileRef): Hash = {
    val file = converter.toPath(fileRef).toFile()
    if (file.exists())
      fromBloopHashToZincHash(ByteHasher.hashFile(file))
    else fromBloopHashToZincHash(0)
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
