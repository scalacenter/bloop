package bloop

import java.io.File

import bloop.io.AbsolutePath
import bloop.util.CacheHashCode
import xsbti.compile.FileHash

/**
 * A compiler oracle is an entity that provides routines to answer
 * questions that come up during the scheduling of compilation tasks.
 */
abstract class CompilerOracle[T] {
  def askForJavaSourcesOfIncompleteCompilations: List[File]
}

object CompilerOracle {
  case class HashedSource(source: AbsolutePath, hash: Int)
  case class Inputs(
      sources: Seq[HashedSource],
      classpath: Seq[FileHash],
      originProjectPath: String,
      originProjectHash: Int
  ) extends CacheHashCode
}
