package bloop.bloopgun.util

object Helper {
  import Ordering.Implicits.seqOrdering
  def seqIntOrdering: Ordering[Seq[Int]] =
    seqOrdering[Seq, Int]
}
