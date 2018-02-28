package bloop.integrations

import scala.reflect.ClassTag

trait Serializable[T] {
  def serialize(value: T): String
  def deserialize(input: Iterator[Char]): T
}

object Serializable {

  import Serializable.{deserialize => d, serialize => s}

  final val SEPARATOR: Char = ' '

  def serialize[T](value: T)(implicit s: Serializable[T]): String =
    s.serialize(value)

  def deserialize[T](input: Iterator[Char])(implicit s: Serializable[T]): T =
    s.deserialize(input)

  implicit val serializableInt: Serializable[Int] = new Serializable[Int] {
    override def serialize(value: Int): String = value.toString + SEPARATOR
    override def deserialize(input: Iterator[Char]): Int = {
      val buffer = new StringBuilder
      var current = '0'
      while ({ current = input.next(); current } != SEPARATOR) buffer.append(current)
      buffer.toString.toInt
    }
  }

  implicit val serializableString: Serializable[String] = new Serializable[String] {
    override def serialize(value: String): String = s(value.length) + value
    override def deserialize(input: Iterator[Char]): String = {
      val length = d[Int](input)
      (0 until length).map(_ => input.next()).mkString
    }
  }

  implicit def serializableArray[T: ClassTag: Serializable]: Serializable[Array[T]] =
    new Serializable[Array[T]] {
      override def serialize(values: Array[T]): String = {
        val length = s(values.length)
        length + values.map(t => s(t)).mkString
      }
      override def deserialize(input: Iterator[Char]): Array[T] = {
        val length = d[Int](input)
        (0 until length).map(_ => d[T](input)).toArray
      }
    }

  implicit def serializableOption[T: ClassTag: Serializable]: Serializable[Option[T]] =
    new Serializable[Option[T]] {
      override def serialize(value: Option[T]): String = s(value.toArray)
      override def deserialize(input: Iterator[Char]): Option[T] = d[Array[T]](input).headOption
    }

}
