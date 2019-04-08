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
      options: Vector[String],
      scalaJars: Vector[String],
      originProjectPath: String
  ) extends CacheHashCode {

    /**
     * Custom hash code that does not take into account ordering of `sources`
     * and `classpath` hashes that can change from run to run since they are
     * computed in parallel. This hash code is cached in a val so that we don't
     * need to recompute it every time we use it in a map/set.
     */
    override lazy val hashCode: Int = {
      import scala.util.hashing.MurmurHash3
      val sourcesHashCode = MurmurHash3.unorderedHash(sources, 0)
      val classpathHashCode = MurmurHash3.unorderedHash(classpath, sourcesHashCode)
      val optionsHashCode = MurmurHash3.unorderedHash(options, classpathHashCode)
      val scalaJarsHashCode = MurmurHash3.unorderedHash(scalaJars, optionsHashCode)
      MurmurHash3.stringHash(originProjectPath, scalaJarsHashCode)
    }

    override def equals(other: Any): Boolean = {
      other match {
        case other: Inputs => this.hashCode == other.hashCode
        case _ => false
      }
    }
  }

  object Inputs {
    def emptyFor(originPath: String): Inputs = {
      Inputs(Vector.empty, Vector.empty, Vector.empty, Vector.empty, originPath)
    }
  }
}
