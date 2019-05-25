package bloop

import bloop.io.AbsolutePath
import bloop.util.CacheHashCode
import xsbti.compile.FileHash

case class UniqueCompileInputs(
    sources: Vector[UniqueCompileInputs.HashedSource],
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
      case other: UniqueCompileInputs => this.hashCode == other.hashCode
      case _ => false
    }
  }
}

object UniqueCompileInputs {
  case class HashedSource(source: AbsolutePath, hash: Int)

  def emptyFor(originPath: String): UniqueCompileInputs = {
    UniqueCompileInputs(Vector.empty, Vector.empty, Vector.empty, Vector.empty, originPath)
  }
}
