package buildpress.io

import java.io.IOException
import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes
import bloop.io.ByteHasher
import buildpress.config.Config.{BuildSettingsHashes, HashedPath}

object SbtProjectHasher {
  class SbtFileMatcher(root: Path) extends PathMatcher {
    override def matches(path: Path): Boolean = {
      val projectDir: Path = root.resolve("project")
      val projectMetaDir: Path = projectDir.resolve("project")
      val parent: Path = path.getParent

      (parent == root || parent == projectDir || parent == projectMetaDir) && {
        val filename: String = path.getFileName.toString
        filename == "build.properties" ||
        filename.endsWith(".sbt") ||
        filename.endsWith(".scala")
      }
    }
  }

  def hashProjectSettings(root: AbsolutePath): BuildSettingsHashes = {
    val individual: List[(AbsolutePath, Int)] =
      SbtProjectHasher.findAndHashSbtFilesInRepo(root.underlying)
    BuildSettingsHashes(individual.map(ph => HashedPath(ph._1.underlying, ph._2)))
  }

  // TODO: hash structure, not plain files
  def findAndHashSbtFilesInRepo(
      path: Path
  ): List[(AbsolutePath, Int)] = {
    val collected = List.newBuilder[Path]
    val pm = new SbtFileMatcher(path)
    val discovery = new FileVisitor[Path] {
      def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult = {
        if (pm.matches(file)) {
          collected += file
        }
        FileVisitResult.CONTINUE
      }

      def visitFileFailed(
          t: Path,
          e: IOException
      ): FileVisitResult = {
        FileVisitResult.CONTINUE
      }

      def preVisitDirectory(
          directory: Path,
          attributes: BasicFileAttributes
      ): FileVisitResult = {
        FileVisitResult.CONTINUE
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = {
        FileVisitResult.CONTINUE
      }
    }

    Files.walkFileTree(
      path,
      java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS),
      Int.MaxValue,
      discovery
    )

    collected
      .mapResult(ps => ps.map(p => AbsolutePath(p) -> ByteHasher.hashFileContents(p.toFile)))
      .result()
  }
}
