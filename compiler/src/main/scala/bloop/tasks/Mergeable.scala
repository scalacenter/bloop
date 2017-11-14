package bloop.tasks

trait Mergeable[T] {
  def merge(inputs: Seq[T]): T
}

object Mergeable {
  implicit def MapMergeable[K, V]: Mergeable[Map[K, V]] =
    (inputs: Seq[Map[K, V]]) => inputs.foldLeft(Map.empty[K, V])(_ ++ _)
}
