package bloop.io

import java.io.{InputStream, SequenceInputStream}
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import java.util.Enumeration
import java.util.zip.{Adler32, CheckedInputStream}

/**
 * Represents the state of a directory and some files in which we track changes.
 *
 * @param dir           The directory to track.
 * @param pattern       The pattern matching the files that must be tracked inside `dir`.
 * @param modifiedTimes The last modification time of the tracked files.
 * @param contentsChecksum The checksum of all the contents of the directory.
 */
final class DirChecksum(dir: AbsolutePath,
                        pattern: String,
                        modifiedTimes: List[(AbsolutePath, FileTime)],
                        contentsChecksum: Long) {

  /**
   * Inspects the directory for changes.
   *
   * @return true if the directory has changed, false otherwise.
   */
  def changed(): DirChecksum.DirStatus = {
    val newModifiedTimes = DirChecksum.getFiles(dir, pattern)
    if (newModifiedTimes == modifiedTimes) DirChecksum.DirUnchanged(None)
    else {
      val newChecksum = DirChecksum.filesChecksum(newModifiedTimes.map(_._1))
      if (newChecksum != contentsChecksum) DirChecksum.DirChanged
      else {
        val checksum = new DirChecksum(dir, pattern, newModifiedTimes, newChecksum)
        DirChecksum.DirUnchanged(Some(checksum))
      }
    }
  }
}

object DirChecksum {

  /** Indicates the status of a tracked directory */
  sealed trait DirStatus

  /**
   * Indicates that the content of the directory hasn't changed.
   *
   * @param newChecksum If set, the new `DirChecksum` with the updated
   *                    `lastModifiedTimes` (if the files have been touched, but
   *                    not modified).
   */
  case class DirUnchanged(newChecksum: Option[DirChecksum]) extends DirStatus

  /** Indicates that the content of the directory has changed. */
  case object DirChanged extends DirStatus

  /**
   * Creates a new `DirChecksum`.
   *
   * @param dir     The directory to track.
   * @param pattern The pattern that must be matched by the tracked files.
   */
  def apply(dir: AbsolutePath, pattern: String): DirChecksum = {
    val files = getFiles(dir, pattern)
    val checksum = filesChecksum(files.map(_._1))
    new DirChecksum(dir, pattern, files, checksum)
  }

  /**
   * Returns all the tracked files inside this directory, associated with their last
   * modification time.
   *
   * @param dir     The directory to track.
   * @param pattern The pattern to find the files to track.
   * @return A map associating each tracked file with its last modification time.
   */
  private def getFiles(dir: AbsolutePath, pattern: String): List[(AbsolutePath, FileTime)] = {
    Paths
      .getAll(dir, pattern)
      .map { path =>
        path -> Files.getLastModifiedTime(path.underlying)
      }
      .toList
  }

  /**
   * Computes the checksum of `files`.
   *
   * @param files The files whose checksum must be computed.
   * @return The checksum.
   */
  private def filesChecksum(files: List[AbsolutePath]): Long = {
    val streams = new Enumeration[InputStream] {
      val streams = files.map(f => Files.newInputStream(f.underlying)).toIterator
      override def hasMoreElements(): Boolean = streams.hasNext
      override def nextElement(): InputStream = streams.next()
    }

    val checkedStream = new CheckedInputStream(new SequenceInputStream(streams), new Adler32)
    try {
      while (checkedStream.read() >= 0) {}
      checkedStream.getChecksum().getValue()
    } finally checkedStream.close()
  }
}
