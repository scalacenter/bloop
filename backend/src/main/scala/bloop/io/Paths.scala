package bloop.io

import java.io.IOException
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{FileSystems, FileVisitResult, FileVisitor, Files, Path}
import java.nio.file.{Paths => NioPaths}

import io.github.soc.directories.ProjectDirectories

object Paths {
  private val projectDirectories = ProjectDirectories.fromProjectName("bloop")
  private def createDirFor(filepath: String): Path = Files.createDirectories(NioPaths.get(filepath))
  final val bloopCacheDir: Path = createDirFor(projectDirectories.projectCacheDir)
  final val bloopDataDir: Path = createDirFor(projectDirectories.projectDataDir)
  final val bloopConfigDir: Path = createDirFor(projectDirectories.projectConfigDir)

  def getCacheDirectory(dirName: String): Path = {
    val dir = bloopCacheDir.resolve(dirName)
    if (!Files.exists(dir)) Files.createDirectory(dir)
    else require(Files.isDirectory(dir), s"File ${dir.toAbsolutePath} is not a directory.")
    dir
  }

  def getAll(base: Path, pattern: String): Array[Path] = {
    val out = collection.mutable.ArrayBuffer.empty[Path]
    val matcher = FileSystems.getDefault.getPathMatcher(pattern)
    val visitor = new FileVisitor[Path] {
      override def preVisitDirectory(directory: Path,
                                     attributes: BasicFileAttributes): FileVisitResult =
        FileVisitResult.CONTINUE

      override def postVisitDirectory(directory: Path, exception: IOException): FileVisitResult =
        FileVisitResult.CONTINUE

      override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (matcher.matches(file)) out += file.toAbsolutePath
        FileVisitResult.CONTINUE
      }

      override def visitFileFailed(file: Path, exception: IOException): FileVisitResult =
        FileVisitResult.CONTINUE
    }
    Files.walkFileTree(base, visitor)
    out.toArray
  }
}
