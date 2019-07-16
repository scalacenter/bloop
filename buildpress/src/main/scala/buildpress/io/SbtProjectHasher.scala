package buildpress.io

import java.io.IOException
import java.nio.file.{Path, PathMatcher, FileVisitor, Files, FileVisitResult, FileVisitOption}
import java.nio.file.attribute.BasicFileAttributes
import bloop.io.{AbsolutePath, ByteHasher}
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
      SbtProjectHasher.hashSbtFilesInRepo(root.underlying)
    BuildSettingsHashes(individual.map(ph => HashedPath(ph._1.underlying, ph._2)))
  }

  /**
   * Hashes any source file that is part of an sbt build (including its meta
   * projects).
   *
   * A file is part of an sbt build if it's a top-level `.sbt`, `.scala` or
   * sbt-dependent file (such as `build.properties) in either the workspace
   * directory or inside a meta project (starting with `project/`). This is
   * just a conservative definition of what it means to be a file part of the
   * build, it's not precise enough to understand the structure and nesting.
   *
   * Here are some regexes illustrating the case:
   *
   * 1. `workspace/\\*.sbt`,
   * 1. `workspace/project/\\*.sbt`
   * 1. `workspace/project/build.properties`
   * 1. `workspace/project/\\*\\* /\\*.scala`
   * 1. `workspace/project/project/\\*.sbt`
   * 1. `workspace/project/project/build.properties`
   * 1. `workspace/project/project/\\*\\* /\\*.scala`
   * 1. `workspace/project/project/project/\\*.sbt`
   * 1. `workspace/project/project/project/build.properties`
   * 1. `workspace/project/project/project/\\*\\* /\\*.scala`
   *
   * And so on...
   *
   * At the moment, the hasher doesn't detect changes in global sbt
   * directories.
   */
  def hashSbtFilesInRepo(
      path: Path
  ): List[(AbsolutePath, Int)] = {
    val collected = List.newBuilder[Path]
    val pm: PathMatcher = new SbtFileMatcher(path)

    val discovery: FileVisitor[Path] = new FileVisitor[Path] {
      // emulating stack with list to keep track
      // of the latest/current visited `project/` directory
      private var visitedProjectDirs: List[Path] = Nil

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
        // have to special-case or we will skip
        // the whole project in the last if branch
        if (directory == path) {
          FileVisitResult.CONTINUE
        } else {
          val dirName: String = directory.getFileName.toString
          // Don't enter the `target` dir ever
          if (dirName == "target") {
            FileVisitResult.SKIP_SUBTREE
          } else if (dirName == "project") {
            visitedProjectDirs = directory :: visitedProjectDirs
            FileVisitResult.CONTINUE
          } else {
            visitedProjectDirs.headOption match {
              // Only enter the directory if it's a child (recursively) of a project dir
              case Some(parentProjectDir) if directory.startsWith(parentProjectDir) =>
                FileVisitResult.CONTINUE
              case _ =>
                FileVisitResult.SKIP_SUBTREE
            }
          }
        }
      }

      def postVisitDirectory(
          directory: Path,
          exception: IOException
      ): FileVisitResult = {
        if (directory.getFileName.toString == "project") {
          visitedProjectDirs = visitedProjectDirs.tail
        }
        FileVisitResult.CONTINUE
      }
    }

    Files.walkFileTree(
      path,
      java.util.EnumSet.noneOf(classOf[FileVisitOption]),
      Int.MaxValue,
      discovery
    )

    // TODO(tkroman): hash structure, not plain files
    collected
      .mapResult(ps => ps.map(p => AbsolutePath(p) -> ByteHasher.hashFileContents(p.toFile)))
      .result()
  }
}
