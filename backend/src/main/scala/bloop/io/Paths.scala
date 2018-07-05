package bloop.io

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{
  DirectoryNotEmptyException,
  FileSystems,
  FileVisitOption,
  FileVisitResult,
  FileVisitor,
  Files,
  Path,
  Paths => NioPaths,
  SimpleFileVisitor
}
import java.util

import io.github.soc.directories.ProjectDirectories

object Paths {
  private val projectDirectories = ProjectDirectories.from("", "", "bloop")
  private def createDirFor(filepath: String): AbsolutePath =
    AbsolutePath(Files.createDirectories(NioPaths.get(filepath)))

  final val bloopCacheDir: AbsolutePath = createDirFor(projectDirectories.cacheDir)
  final val bloopDataDir: AbsolutePath = createDirFor(projectDirectories.dataDir)
  final val bloopLogsDir: AbsolutePath = createDirFor(bloopDataDir.resolve("logs").syntax)
  final val bloopConfigDir: AbsolutePath = createDirFor(projectDirectories.configDir)

  def getCacheDirectory(dirName: String): AbsolutePath = {
    val dir = bloopCacheDir.resolve(dirName)
    val dirPath = dir.underlying
    if (!Files.exists(dirPath)) Files.createDirectory(dirPath)
    else require(Files.isDirectory(dirPath), s"File '${dir.syntax}' is not a directory.")
    dir
  }

  /**
    * Get all files under `base` that match the pattern `pattern` up to depth `maxDepth`.
    *
    * Example:
    * ```
    * Paths.getAll(src, "glob:**.{scala,java}")
    * ```
    */
  def getAllFiles(
      base: AbsolutePath,
      pattern: String,
      maxDepth: Int = Int.MaxValue
  ): List[AbsolutePath] = {
    val out = collection.mutable.ListBuffer.empty[AbsolutePath]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)

    val visitor = new FileVisitor[Path] {
      override def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = FileVisitResult.CONTINUE

      override def postVisitDirectory(directory: Path, exception: IOException): FileVisitResult =
        FileVisitResult.CONTINUE

      override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file)) out += AbsolutePath(file)
        FileVisitResult.CONTINUE
      }

      override def visitFileFailed(file: Path, exception: IOException): FileVisitResult =
        FileVisitResult.CONTINUE
    }

    val opts = util.EnumSet.of(FileVisitOption.FOLLOW_LINKS)
    Files.walkFileTree(base.underlying, opts, maxDepth, visitor)
    out.toList
  }

  /**
   * Recursively delete `path` and all its content.
   *
   * @param path The path to delete
   */
  def delete(path: AbsolutePath): Unit = {
    Files.walkFileTree(
      path.underlying,
      new SimpleFileVisitor[Path] {
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          Files.delete(file)
          FileVisitResult.CONTINUE
        }

        override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
          try Files.delete(dir)
          catch { case _: DirectoryNotEmptyException => () } // Happens sometimes on Windows?
          FileVisitResult.CONTINUE
        }
      }
    )
    ()
  }
}
