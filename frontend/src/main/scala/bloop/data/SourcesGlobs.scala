package bloop.data

import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Path
import bloop.config.Config
import scala.util.Properties

case class SourcesGlobs(
    directory: Path,
    walkDepth: Int,
    includes: List[PathMatcher],
    excludes: List[PathMatcher]
) {
  def matches(path: Path): Boolean = {
    def matchesList(lst: List[PathMatcher]): Boolean = lst match {
      case Nil => false
      case head :: tail =>
        if (head.matches(path)) true
        else matchesList(tail)
    }
    matchesList(includes) && !matchesList(excludes)
  }
}

object SourcesGlobs {
  def fromConfig(project: Config.Project): List[SourcesGlobs] = {
    project.sourcesGlobs match {
      case None => Nil
      case Some(globs) =>
        globs.flatMap { glob =>
          SourcesGlobs.fromStrings(
            glob.directory,
            glob.walkDepth,
            glob.includes,
            glob.excludes
          )
        }
    }
  }

  def fromStrings(
      directory: Path,
      walkDepth: Option[Int],
      includes: List[String],
      excludes: List[String]
  ): List[SourcesGlobs] = {
    if (includes.isEmpty) Nil
    else {
      val uriPath = directory.toUri().getPath()
      val basePath =
        if (Properties.isWin) uriPath.stripPrefix("/")
        else uriPath
      val prefix = s"glob:${basePath}"
      val fs = FileSystems.getDefault()
      def newPathMatcher(glob: String): PathMatcher = {
        fs.getPathMatcher(prefix + glob)
      }
      List(
        SourcesGlobs(
          directory,
          walkDepth.getOrElse(Int.MaxValue),
          includes = includes.map(newPathMatcher),
          excludes = excludes.map(newPathMatcher)
        )
      )
    }
  }
}
