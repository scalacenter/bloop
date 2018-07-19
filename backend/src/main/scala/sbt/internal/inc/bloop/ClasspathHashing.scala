package sbt.internal.inc.bloop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.attribute.{BasicFileAttributes, FileTime}

import monix.eval.Task
import xsbti.compile.FileHash
import sbt.internal.inc.{EmptyStamp, Stamper}
import sbt.io.IO

object ClasspathHashing {
  // For more safety, store both the time and size
  private type JarMetadata = (FileTime, Long)
  private[this] val cacheMetadataJar = new ConcurrentHashMap[File, (JarMetadata, FileHash)]()
  private[this] final val emptyStampCode = EmptyStamp.hashCode()
  private def emptyFileHash(file: File) = FileHash.of(file, emptyStampCode)
  private def genFileHash(file: File, metadata: JarMetadata): FileHash = {
    val newHash = FileHash.of(file, Stamper.forHash(file).hashCode())
    cacheMetadataJar.put(file, (metadata, newHash))
    newHash
  }

  /**
    * Hash the classpath and parallelize with Monix's task.
    *
    * The whole way caching works in Zinc is ridiculous and in the future we should pull off
    * the strategy described in https://github.com/sbt/zinc/pull/371 and just use xxHash instead
    * of the expensive SHA-1. The most critical thing to change is the fact that we do `.hashCode`
    * after we've collected the stamps (so we're computing the hash code of an effectively more
    * precise hash of the files!). The public interface of `FileHash` needs to be changed to fix
    * this issue, that will prove more necessary when we can pass the list of hashes to the compiler
    * directly.
    *
    * @param classpath The list of files to be hashed (if they exist).
    * @return A task returning a list of hashes.
    */
  def hash(classpath: Seq[File]): Task[Seq[FileHash]] = {
    // #433: Cache jars with their metadata to avoid recomputing hashes transitively in other projects
    def fromCacheOrHash(file: File): Task[FileHash] = {
      if (!file.exists()) Task.now(emptyFileHash(file))
      else {
        Task {
          // `readAttributes` needs to be guarded by `file.exists()`, otherwise it fails
          val attrs = Files.readAttributes(file.toPath, classOf[BasicFileAttributes])
          if (attrs.isDirectory) emptyFileHash(file)
          else {
            val currentMetadata =
              (FileTime.fromMillis(IO.getModifiedTimeOrZero(file)), attrs.size())
            Option(cacheMetadataJar.get(file)) match {
              case Some((metadata, hashHit)) if metadata == currentMetadata => hashHit
              case _ => genFileHash(file, currentMetadata)
            }
          }
        }
      }
    }

    Task.gatherUnordered(classpath.map(fromCacheOrHash(_)))
  }
}
