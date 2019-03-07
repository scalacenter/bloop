package sbt.internal.inc.bloop.internal

import xsbti.compile.{Changes, CompileAnalysis, FileHash, MiniSetup}
import sbt.internal.inc.{CompileConfiguration, LookupImpl}

final class BloopLookup(
    compileConfiguration: CompileConfiguration,
    previousSetup: Option[MiniSetup]
) extends LookupImpl(compileConfiguration, previousSetup) {
  private val classpathHash: Vector[FileHash] =
    compileConfiguration.currentSetup.options.classpathHash.toVector
  override def changedClasspathHash: Option[Vector[FileHash]] = {
    if (classpathHash == previousClasspathHash) None
    else {
      // Previous classpath hash may contain directories, discard them and check again
      val newPreviousClasspathHash = previousClasspathHash.filterNot(fh => fh.file.isDirectory())
      if (classpathHash == newPreviousClasspathHash) None
      else Some(classpathHash)
    }
  }
}
