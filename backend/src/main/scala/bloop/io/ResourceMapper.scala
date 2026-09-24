package bloop.io

import java.nio.file.Files
import java.nio.file.StandardCopyOption

import scala.util.control.NonFatal

import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task

/**
 * Utility for handling resource file mappings.
 *
 * Resource mappings allow files to be copied from source locations to custom
 * target paths, similar to SBT's "mappings" field.
 */
object ResourceMapper {
  private implicit val filter: DebugFilter.All.type = DebugFilter.All

  /**
   * Copy mapped resources to the target directory.
   *
   * @param mappings List of (source, targetRelativePath) tuples
   * @param classesDir Base directory where resources should be copied
   * @param logger Logger for debug/error messages
   * @return Task that completes when all resources are copied
   */
  def copyMappedResources(
      mappings: List[(AbsolutePath, String)],
      classesDir: AbsolutePath,
      logger: Logger
  ): Task[Unit] = {
    val tasks = mappings.map {
      case (source, targetRelPath) =>
        Task {
          val target = classesDir.resolve(targetRelPath)
          if (!target.getParent.exists) {
            Files.createDirectories(target.getParent.underlying)
          }

          if (source.isDirectory) {
            import java.nio.file.FileVisitResult
            import java.nio.file.Path
            import java.nio.file.SimpleFileVisitor
            import java.nio.file.attribute.BasicFileAttributes

            val sourcePath = source.underlying
            val targetPath = target.underlying
            Files.walkFileTree(
              sourcePath,
              new SimpleFileVisitor[Path] {
                override def visitFile(
                    file: Path,
                    attrs: BasicFileAttributes
                ): FileVisitResult = {
                  val relPath = sourcePath.relativize(file)
                  val targetFile = targetPath.resolve(relPath)
                  Files.createDirectories(targetFile.getParent)
                  Files.copy(file, targetFile, StandardCopyOption.REPLACE_EXISTING)
                  FileVisitResult.CONTINUE
                }
              }
            )
          } else if (source.exists) {
            Files.copy(source.underlying, target.underlying, StandardCopyOption.REPLACE_EXISTING)
          } else {
            logger.warn(s"Source file $source does not exist, skipping mapping to $targetRelPath")
          }
          ()
        }
    }
    Task.gatherUnordered(tasks).map(_ => ())
  }

  /**
   * Check if any mapped resources have changed since the given timestamp.
   *
   * Note: This is currently not used. Incremental compilation change detection
   * for resource mappings would require integration with Zinc's analysis.
   * For now, mapped resources are always copied during compilation.
   *
   * @param mappings List of (source, targetRelativePath) tuples
   * @param lastModified Timestamp to compare against
   * @return true if any source file is newer than lastModified
   */
  private[bloop] def hasMappingsChanged(
      mappings: List[(AbsolutePath, String)],
      lastModified: Long
  ): Boolean = {
    import java.nio.file.Files
    mappings.exists {
      case (source, _) =>
        if (source.isDirectory) {
          hasDirectoryChanged(source, lastModified)
        } else {
          source.exists && Files.getLastModifiedTime(source.underlying).toMillis > lastModified
        }
    }
  }

  private def hasDirectoryChanged(dir: AbsolutePath, lastModified: Long): Boolean = {
    if (dir.exists) {
      val stream = Files.walk(dir.underlying)
      try {
        stream.anyMatch(path => Files.getLastModifiedTime(path).toMillis > lastModified)
      } finally {
        stream.close()
      }
    } else {
      false
    }
  }
}
