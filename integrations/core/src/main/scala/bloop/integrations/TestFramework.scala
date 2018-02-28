package bloop.integrations

final class TestFramework(val implClasses: Array[String])

object TestFramework {
  implicit val serializableTestFramework: Serializable[TestFramework] =
    new Serializable[TestFramework] {
      override def serialize(v: TestFramework): String = Serializable.serialize(v.implClasses)
      override def deserialize(input: Iterator[Char]): TestFramework =
        new TestFramework(Serializable.deserialize[Array[String]](input))
    }
}
