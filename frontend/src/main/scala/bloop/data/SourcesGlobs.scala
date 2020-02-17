package bloop.data

import java.nio.file.FileSystems
import java.nio.file.PathMatcher
import java.nio.file.Path
import bloop.config.Config
import scala.util.Properties
import bloop.io.AbsolutePath

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
}

object SourcesGlobs {
  def fromConfig(project: Config.Project): List[SourcesGlobs] = {
    project.sourcesGlobs match {
      case None => Nil
      case Some(globs) =>
        globs.flatMap { glob =>
          SourcesGlobs.fromStrings(
            AbsolutePath(glob.directory),
            glob.walkDepth,
            glob.includes,
            glob.excludes
          )
        }
    }
  }

  def fromStrings(
      directory: AbsolutePath,
      walkDepth: Option[Int],
      includes: List[String],
      excludes: List[String]
  ): List[SourcesGlobs] = {
    if (includes.isEmpty) Nil
    else {
      val fs = FileSystems.getDefault()
      List(
        SourcesGlobs(
          directory,
          walkDepth.getOrElse(Int.MaxValue),
          includes = includes.map(fs.getPathMatcher),
          excludes = excludes.map(fs.getPathMatcher)
        )
      )
    }
  }
}
