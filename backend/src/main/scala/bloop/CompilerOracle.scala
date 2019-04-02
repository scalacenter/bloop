package bloop

import java.io.File

import bloop.io.AbsolutePath
import bloop.util.CacheHashCode
import xsbti.compile.FileHash

/**
 * A compiler oracle is an entity that provides routines to answer
 * questions that come up during the scheduling of compilation tasks.
 */
abstract class CompilerOracle {
  def askForJavaSourcesOfIncompleteCompilations: List[File]
}

object CompilerOracle {
  case class HashedSource(source: AbsolutePath, hash: Int)
  case class Inputs(
      sources: Vector[HashedSource],
      classpath: Vector[FileHash],
      originProjectPath: String,
      originProjectHash: Int
  ) extends CacheHashCode {

    /**
     * Custom hash code that does not take into account ordering of `sources`
     * and `classpath` hashes that can change from run to run since they are
     * computed in parallel. This hash code is cached in a val so that we don't
     * need to recompute it every time we use it in a map/set.
     */
    override lazy val hashCode: Int = {
      import scala.util.hashing.MurmurHash3
      val initialHashCode = originProjectHash.hashCode
      val sourcesHashCode = MurmurHash3.unorderedHash(sources, initialHashCode)
      val classpathHashCode = MurmurHash3.unorderedHash(classpath, sourcesHashCode)
      MurmurHash3.stringHash(originProjectPath, classpathHashCode)
    }

    override def equals(other: Any): Boolean = {
      other match {
        case other: Inputs => this.hashCode == other.hashCode
        case _ => false
      }
    }
  }

  object Inputs {
    def emptyFor(originPath: String, projectHash: Int): Inputs = {
      Inputs(Vector.empty, Vector.empty, originPath, projectHash)
    }
  }
}
