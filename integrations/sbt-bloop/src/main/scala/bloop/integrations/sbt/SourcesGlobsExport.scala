package bloop.integrations.sbt

import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

import scala.collection.mutable

import bloop.config.Config

import sbt.io.AllPassFilter
import sbt.io.ExactFilter
import sbt.io.ExtensionFilter
import sbt.io.FileFilter
import sbt.io.NothingFilter
import sbt.io.OrFilter
import sbt.io.OrNameFilter
import sbt.io.PatternFilter
import sbt.io.PrefixFilter
import sbt.io.SuffixFilter

/**
 * Translates the effect of sbt source file filters into bloop sources globs.
 *
 * The bloop server compiles every non-hidden `*.scala`/`*.java` file under an exported source
 * directory. When sbt file filters hide files from `Keys.sources`, exporting the plain directory
 * would make bloop compile more than sbt does. Those directories are exported as sources globs
 * that include everything the server would pick up by default and exclude what sbt filters out.
 *
 * Name-based filters are translated into glob patterns, so they also apply to files created
 * after the export. Filters that are arbitrary predicates cannot be evaluated against files
 * that do not exist yet; their effect is exported as the files and directories they reject at
 * export time. A file is only excluded this way when it is absent from what sbt compiles *and*
 * the filters reject it, so that a file created while the export runs is never excluded by
 * accident.
 */
object SourcesGlobsExport {

  /* The server matches sources in plain directories by file name: non-hidden files ending in
   * `.scala` or `.java` at any depth. Relative-path globs need an extra pattern for top-level
   * files because `**` followed by a separator requires at least one directory in the matched
   * path. */
  private[sbt] val defaultIncludes: List[String] =
    List("glob:[!.]*.{scala,java}", "glob:**/[!.]*.{scala,java}")

  private def isServerVisibleSource(file: Path): Boolean = {
    val name = file.getFileName.toString
    !name.startsWith(".") && (name.endsWith(".scala") || name.endsWith(".java"))
  }

  /**
   * Splits source directories into those exported as plain directories and those that must
   * become sources globs because the server would otherwise compile files that sbt filters out.
   *
   * Only unmanaged source directories are inspected: file filters do not apply to managed
   * sources, and stale generator leftovers on disk must not be baked into the exported
   * configuration. Directories without filtered content are returned untouched so that builds
   * with default filters keep generating identical configuration files.
   */
  def splitDirectoriesIntoGlobs(
      sourceDirs: Seq[Path],
      unmanagedSourceDirs: Seq[Path],
      sbtSources: Seq[Path],
      includeFilter: FileFilter,
      excludeFilter: FileFilter
  ): (List[Path], Option[List[Config.SourcesGlobs]]) = {
    val includeAccepts = (path: Path) => includeFilter.accept(path.toFile)
    val excludeAccepts = (path: Path) => excludeFilter.accept(path.toFile)
    val nameGlobs = excludeNameGlobs(excludeFilter)
    val nameMatchers =
      nameGlobs.map(glob => FileSystems.getDefault.getPathMatcher("glob:" + glob))
    def coveredByNameGlobs(path: Path): Boolean =
      nameMatchers.exists(_.matches(path.getFileName))
    /* Name globs match files and directories at any depth, exactly like sbt name filters do
     * during source collection. */
    val patternExcludes = nameGlobs.flatMap { glob =>
      List(s"glob:$glob", s"glob:**/$glob", s"glob:$glob/**", s"glob:**/$glob/**")
    }

    /* A custom include filter is exported only when the whole filter translates: a partial
     * translation would make the server compile less than sbt does. The default filter keeps
     * the server's own includes so that configurations do not change. */
    val translatedIncludes = exactNameGlobs(includeFilter)
      .filter(_.toSet != Set("*.java", "*.scala"))
    val includeMatchers = translatedIncludes.map(
      _.map(glob => FileSystems.getDefault.getPathMatcher("glob:" + glob))
    )
    val entryIncludes = translatedIncludes match {
      case Some(globs) => globs.flatMap(glob => List(s"glob:$glob", s"glob:**/$glob"))
      case None => defaultIncludes
    }
    /* Translated includes lose the non-hidden guard that the default includes carry, so the
     * server's rule of never compiling hidden files is restored with explicit excludes. */
    val hiddenFileExcludes = translatedIncludes match {
      case Some(_) => List("glob:.*", "glob:**/.*")
      case None => Nil
    }
    def coveredByIncludes(file: Path): Boolean = includeMatchers match {
      case Some(matchers) => !matchers.exists(_.matches(file.getFileName))
      case None => false
    }

    val compiledBySbt = sbtSources.map(_.toAbsolutePath.normalize).toSet
    val dirsWithGlobs = unmanagedSourceDirs.distinct.flatMap { dir =>
      if (!Files.isDirectory(dir)) None
      else {
        val normalizedDir = dir.toAbsolutePath.normalize
        val walk = walkDirectory(dir, compiledBySbt, excludeAccepts)
        val snapshotFiles = walk.unexpectedFiles.filter { file =>
          val rejected = !includeAccepts(file) || excludeAccepts(file)
          rejected && !coveredByNameGlobs(file) && !coveredByIncludes(file)
        }
        val subtreeDirs = walk.rejectedDirectories.filterNot(coveredByNameGlobs)
        val convert = snapshotFiles.nonEmpty || subtreeDirs.nonEmpty ||
          patternExcludes.nonEmpty || translatedIncludes.isDefined
        if (!convert) None
        else {
          val excludes = (
            patternExcludes ++
              hiddenFileExcludes ++
              snapshotFiles
                .map(file => "glob:" + escapeGlob(relativeUnixPath(normalizedDir, file))) ++
              subtreeDirs
                .map(d => "glob:" + escapeGlob(relativeUnixPath(normalizedDir, d)) + "/**")
          ).distinct.sorted
          Some(dir -> Config.SourcesGlobs(dir, None, entryIncludes, excludes))
        }
      }
    }
    val converted = dirsWithGlobs.map(_._1).toSet
    val plainDirs = sourceDirs.filterNot(converted).toList
    val globs = dirsWithGlobs.map(_._2).toList
    (plainDirs, if (globs.isEmpty) None else Some(globs))
  }

  /**
   * Exact translation of a file filter into file-name globs: the globs match precisely the
   * names the filter accepts, or `None` when any part of the filter is not name-based.
   */
  private[sbt] def exactNameGlobs(filter: FileFilter): Option[List[String]] = filter match {
    case f: OrFilter =>
      for { l <- exactNameGlobs(f.left); r <- exactNameGlobs(f.right) } yield l ++ r
    case f: OrNameFilter =>
      for { l <- exactNameGlobs(f.left); r <- exactNameGlobs(f.right) } yield l ++ r
    case f: ExactFilter => Some(List(escapeGlob(f.matchName)))
    case f: PrefixFilter => Some(List(escapeGlob(f.prefix) + "*"))
    case f: SuffixFilter => Some(List("*" + escapeGlob(f.suffix)))
    case f: ExtensionFilter => Some(f.extensions.map(ext => "*." + escapeGlob(ext)).toList)
    case f: PatternFilter if f.parts.nonEmpty =>
      // GlobFilter keeps the original wildcard-separated parts of the expression
      Some(List(f.parts.map(escapeGlob).mkString("*")))
    case AllPassFilter => Some(List("*"))
    case NothingFilter => Some(Nil)
    case _ => None
  }

  /**
   * Best-effort translation of an exclude filter into file-name globs. The returned globs
   * never match a name that the filter accepts, so applying them as excludes can only hide
   * what sbt hides; untranslatable parts (arbitrary predicates, conjunctions, negations)
   * contribute nothing and are handled by the export-time snapshot instead.
   */
  private[sbt] def excludeNameGlobs(filter: FileFilter): List[String] = filter match {
    case f: OrFilter => excludeNameGlobs(f.left) ++ excludeNameGlobs(f.right)
    case f: OrNameFilter => excludeNameGlobs(f.left) ++ excludeNameGlobs(f.right)
    case other => exactNameGlobs(other).getOrElse(Nil)
  }

  private case class DirectoryWalk(
      unexpectedFiles: List[Path],
      rejectedDirectories: List[Path]
  )

  /**
   * Collects the files under `dir` that the server would compile but sbt does not, and the
   * directories that the exclude filter rejects. Rejected directories are not walked into:
   * their whole subtree is excluded, which also covers files created under them later.
   */
  private def walkDirectory(
      dir: Path,
      compiledBySbt: Set[Path],
      excludeAccepts: Path => Boolean
  ): DirectoryWalk = {
    val files = mutable.ListBuffer.empty[Path]
    val rejectedDirs = mutable.ListBuffer.empty[Path]
    val root = dir.toAbsolutePath.normalize
    Files.walkFileTree(
      dir,
      java.util.EnumSet.of(FileVisitOption.FOLLOW_LINKS),
      Int.MaxValue,
      new SimpleFileVisitor[Path] {
        override def preVisitDirectory(
            directory: Path,
            attrs: BasicFileAttributes
        ): FileVisitResult = {
          val normalized = directory.toAbsolutePath.normalize
          if (normalized == root) FileVisitResult.CONTINUE
          else if (excludeAccepts(normalized)) {
            rejectedDirs += normalized
            FileVisitResult.SKIP_SUBTREE
          } else FileVisitResult.CONTINUE
        }
        override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
          if (isServerVisibleSource(file)) {
            val normalized = file.toAbsolutePath.normalize
            if (!compiledBySbt.contains(normalized)) files += normalized
          }
          FileVisitResult.CONTINUE
        }
        override def visitFileFailed(file: Path, e: IOException): FileVisitResult =
          FileVisitResult.CONTINUE
        // A failure while reading a directory must not fail the export; a file
        // the walk misses only means one fewer exclude
        override def postVisitDirectory(directory: Path, e: IOException): FileVisitResult =
          FileVisitResult.CONTINUE
      }
    )
    DirectoryWalk(files.result(), rejectedDirs.result())
  }

  /** Relative path using `/` separators, which glob matchers accept on all platforms. */
  private[sbt] def relativeUnixPath(dir: Path, file: Path): String = {
    val relative = dir.relativize(file)
    0.until(relative.getNameCount).map(relative.getName(_).toString).mkString("/")
  }

  /** Escapes glob metacharacters so that the path fragment is matched literally. */
  private[sbt] def escapeGlob(path: String): String = {
    val out = new StringBuilder(path.length)
    path.foreach {
      case c @ ('\\' | '*' | '?' | '[' | ']' | '{' | '}' | ',') => out.append('\\').append(c)
      case c => out.append(c)
    }
    out.toString
  }
}
