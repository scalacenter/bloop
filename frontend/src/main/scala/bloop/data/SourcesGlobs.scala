package bloop.data

import java.nio.file._
import java.nio.file.attribute.BasicFileAttributes

import scala.util.control.NonFatal

import bloop.config.Config
import bloop.io.AbsolutePath
import bloop.logging.Logger

case class SourcesGlobs(
    directory: AbsolutePath,
    walkDepth: Int,
    includes: List[PathMatcher],
    excludes: List[PathMatcher]
) {
  def matches(path: Path): Boolean = {
    val relativePath = AbsolutePath(path).toRelative(directory)
    def matchesList(lst: List[PathMatcher]): Boolean = lst match {
      case Nil => false
      case head :: tail =>
        if (head.matches(relativePath.underlying)) true
        else matchesList(tail)
    }
    matchesList(includes) && !matchesList(excludes)
  }

  def walkThrough(f: AbsolutePath => Unit): Unit = {
    if (directory.isDirectory) {
      val _ = Files.walkFileTree(
        directory.underlying,
        java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        walkDepth,
        new SimpleFileVisitor[Path] {
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            if (matches(file)) f(AbsolutePath(file))
            FileVisitResult.CONTINUE
          }
        }
      )
    }
  }
}

object SourcesGlobs {
  def fromConfig(project: Config.Project, logger: Logger): List[SourcesGlobs] = {
    project.sourcesGlobs match {
      case None => Nil
      case Some(globs) =>
        globs.flatMap { glob =>
          SourcesGlobs.fromStrings(
            project.name,
            AbsolutePath(glob.directory),
            glob.walkDepth,
            glob.includes,
            glob.excludes,
            logger
          )
        }
    }
  }

  def fromStrings(
      projectName: String,
      directory: AbsolutePath,
      walkDepth: Option[Int],
      includes: List[String],
      excludes: List[String],
      logger: Logger
  ): List[SourcesGlobs] = {
    if (includes.isEmpty) Nil
    else {
      try {
        val fs = FileSystems.getDefault()
        List(
          SourcesGlobs(
            directory,
            walkDepth.getOrElse(Int.MaxValue),
            includes = includes.map(fs.getPathMatcher),
            excludes = excludes.map(fs.getPathMatcher)
          )
        )
      } catch {
        case NonFatal(e) =>
          logger.error(
            s"Ignoring invalid 'sourcesGlobs' object containing directory '$directory' in project '$projectName'"
          )
          logger.trace(e)
          Nil
      }
    }
  }
}
