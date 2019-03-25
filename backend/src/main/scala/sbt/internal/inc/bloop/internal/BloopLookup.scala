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
  override def changedClasspathHash: Option[Vector[FileHash]] = {
    if (classpathHash == previousClasspathHash) None
    else {
      // Previous classpath hash may contain directories, discard them and check again
      val newPreviousClasspathHash = previousClasspathHash.filterNot(fh => fh.file.isDirectory())
      if (classpathHash == newPreviousClasspathHash) None
      else {
        logger.info("Classpath hash changed!")
        val previousClasspath = pprint.apply(newPreviousClasspathHash, height = Int.MaxValue).render
        val newClasspath = pprint.apply(classpathHash, height = Int.MaxValue).render
        logger.info(Diff.unifiedDiff(previousClasspath, newClasspath, "", ""))
        Some(classpathHash)
      }
    }
  }
}
