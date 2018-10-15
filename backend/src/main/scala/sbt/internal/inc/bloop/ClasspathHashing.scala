package sbt.internal.inc.bloop

import java.io.{File, InputStream}
import java.nio.file.{Files, NoSuchFileException}
import java.util.concurrent.ConcurrentHashMap
import java.nio.file.attribute.{BasicFileAttributes, FileTime}
import java.util.zip.ZipEntry

import monix.eval.Task
import org.zeroturnaround.zip.commons.IOUtils
import org.zeroturnaround.zip.{ZipEntryCallback, ZipUtil}
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
          try {
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
          } catch {
            // Exception can be thrown if between `file.exists` and `readAttributes` a file is gone
            case _: NoSuchFileException => emptyFileHash(file)
          }
        }
      }
    }

    // Use gather instead of gather unordered to return results in the right input order
    Task.gather(classpath.map(fromCacheOrHash(_)))
  }

  private[this] val definedMacrosJarCache = new ConcurrentHashMap[File, (JarMetadata, Boolean)]()

  // From https://raw.githubusercontent.com/twitter/elephant-bird/master/core/src/main/java/com/twitter/elephantbird/util/StreamSearcher.java
  val blackboxReference = "scala/reflect/macros/blackbox/Context".getBytes
  val whiteboxReference = "scala/reflect/macros/whitebox/Context".getBytes
  def containsMacroDefinition(classpath: Seq[File]): Task[Seq[(File, Boolean)]] = {
    def readJar(jar: File): Task[(File, Boolean)] = Task {
      if (!jar.exists()) sys.error(s"File ${jar} doesn't exist")
      else {
        def detectMacro(jar: File): Boolean = {
          var found: Boolean = false
          ZipUtil.iterate(
            jar,
            new ZipEntryCallback {
              override def process(in: InputStream, zipEntry: ZipEntry): Unit = {
                if (found) ()
                else if (zipEntry.isDirectory) ()
                else if (!zipEntry.getName.endsWith(".class")) ()
                else {
                  try {
                    val bytes = IOUtils.toByteArray(in)
                    found = {
                      bytes.containsSlice(blackboxReference) ||
                      bytes.containsSlice(whiteboxReference)
                    }
                  } catch {
                    case t: Throwable => println(s"Error in ${t}")
                  }
                }
              }
            }
          )
          found
        }

        val attrs = Files.readAttributes(jar.toPath, classOf[BasicFileAttributes])
        val currentMetadata = (FileTime.fromMillis(IO.getModifiedTimeOrZero(jar)), attrs.size())

        Option(definedMacrosJarCache.get(jar)) match {
          case Some((metadata, hit)) if metadata == currentMetadata => jar -> hit
          case _ =>
            val detected = detectMacro(jar)
            definedMacrosJarCache.put(jar, (currentMetadata, detected))
            jar -> detected
        }
      }
    }

    Task.gatherUnordered(classpath.map(readJar(_)))
  }
}
