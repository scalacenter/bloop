package bloop.bloopgun.util

object Helper {
  import Ordering.Implicits.seqDerivedOrdering
  def seqIntOrdering: Ordering[Seq[Int]] =
    seqDerivedOrdering[Seq, Int]
}
