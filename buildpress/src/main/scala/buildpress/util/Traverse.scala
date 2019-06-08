package buildpress.util

import scala.collection.mutable

object Traverse {
  implicit class TraverseOps[T](private val xs: List[T]) extends AnyVal {
    def eitherTraverse[L, R](f: T => Either[L, R]): Either[L, List[R]] = {
      val acc: mutable.Builder[R, List[R]] = List.newBuilder[R]
      var e: Either[L, List[R]] = null
      val it: Iterator[T] = xs.iterator

      while (e == null && it.hasNext) {
        f(it.next()) match {
          case l if l.isLeft => e = l.asInstanceOf[Either[L, List[R]]]
          case Right(v) => acc += v
        }
      }

      if (e == null) {
        Right(acc.result())
      } else {
        e
      }
    }

    def eitherFlatTraverse[L, R](f: T => Either[L, List[R]]): Either[L, List[R]] = {
      val acc: mutable.Builder[R, List[R]] = List.newBuilder[R]
      var e: Either[L, List[R]] = null
      val it: Iterator[T] = xs.iterator

      while (e == null && it.hasNext) {
        f(it.next()) match {
          case l if l.isLeft => e = l.asInstanceOf[Either[L, List[R]]]
          case Right(v) => acc ++= v
        }
      }

      if (e == null) {
        Right(acc.result())
      } else {
        e
      }
    }
  }
}
