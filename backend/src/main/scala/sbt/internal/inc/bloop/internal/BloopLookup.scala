package sbt.internal.inc.bloop.internal

import bloop.util.Diff
import bloop.CompileOutPaths
import bloop.io.AbsolutePath
import bloop.logging.{DebugFilter, Logger}

import xsbti.compile.{Changes, CompileAnalysis, FileHash, MiniSetup}
import sbt.internal.inc.{CompileConfiguration, LookupImpl}

final class BloopLookup(
    compileConfiguration: CompileConfiguration,
    previousSetup: Option[MiniSetup],
    logger: Logger
) extends LookupImpl(compileConfiguration, previousSetup) {
  implicit val filter: DebugFilter = DebugFilter.Compilation
  private val classpathHash: Vector[FileHash] =
    compileConfiguration.currentSetup.options.classpathHash.toVector
  override def changedClasspathHash: Option[Vector[FileHash]] = {
    if (classpathHash == previousClasspathHash) None
    else {
      // Discard directories and known empty classes dirs and check if there's still a hash mismatch
      val newPreviousClasspathHash =
        BloopLookup.filterOutDirsFromHashedClasspath(previousClasspathHash)

      if (classpathHash == newPreviousClasspathHash) None
      else {
        logger.debug("Classpath hash changed!")
        val previousClasspath = pprint.apply(newPreviousClasspathHash, height = Int.MaxValue).render
        val newClasspath = pprint.apply(classpathHash, height = Int.MaxValue).render
        logger.debug(Diff.unifiedDiff(previousClasspath, newClasspath, "", ""))
        Some(classpathHash)
      }
    }
  }
}

object BloopLookup {
  def filterOutDirsFromHashedClasspath(classpath: Seq[FileHash]): Seq[FileHash] = {
    classpath.filterNot { fh =>
      // If directory hash matches, filter it out (directory hash changes with different
      // bloop server sessions, it's just an optimization to avoid checking for isDir
      BloopStamps.isDirectoryHash(fh) ||
      // If directory exists, filter it out
      fh.file.isDirectory() ||
      // If directory is empty classes dir, filter it out
      CompileOutPaths.hasEmptyClassesDir(AbsolutePath(fh.file.toPath))
    }
  }
}
