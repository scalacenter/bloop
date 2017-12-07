package bloop.engine.tasks

trait Mergeable[T] {
  def merge(inputs: Seq[T]): T
}

object Mergeable {
  implicit def MapMergeable[K, V]: Mergeable[Map[K, V]] =
    (inputs: Seq[Map[K, V]]) => inputs.foldLeft(Map.empty[K, V])(_ ++ _)
  implicit def SeqMergeable[T]: Mergeable[Seq[T]] =
    (inputs: Seq[Seq[T]]) => inputs.flatten
}
