package bloop.util

trait CacheHashCode {
  self: Product =>
  // Cache hash code here so that `Dag.toDotGraph` doesn't recompute it all the time
  override lazy val hashCode: Int = scala.util.hashing.MurmurHash3.productHash(self)
}
