package sbt.internal.inc.bloop.internal

import bloop.util.Diff
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
  private val ClassesEmptyDirPrefix = java.io.File.separator + "classes-empty-"
  override def changedClasspathHash: Option[Vector[FileHash]] = {
    if (classpathHash == previousClasspathHash) None
    else {
      // Discard directories and known empty classes dirs and check if there's still a hash mismatch
      val newPreviousClasspathHash = previousClasspathHash.filterNot { fh =>
        // If directory exists, filter it out
        fh.file.isDirectory() ||
        /* Empty classes dirs don't exist so match on path.
         *
         * Don't match on `getFileName` because `classes-empty` is followed by
         * target name, which could contains `java.io.File.separator`, making
         * `getFileName` pick the suffix after the latest separator.
         *
         * e.g. if target name is
         * `util/util-function/src/main/java/com/twitter/function:function`
         * classes empty dir path will be
         * `classes-empty-util/util-function/src/main/java/com/twitter/function:function`.
         */
        fh.file.getAbsolutePath.contains(ClassesEmptyDirPrefix)
      }

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
