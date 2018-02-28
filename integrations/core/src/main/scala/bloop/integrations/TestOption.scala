package bloop.integrations

sealed trait TestOption

object TestOption {
  implicit val testOptionSerializable: Serializable[TestOption] = new Serializable[TestOption] {
    override def serialize(v: TestOption): String = v match {
      case ex: Exclude =>
        Serializable.serialize("exclude") + Serializable.serialize(ex)

      case arg: Argument =>
        Serializable.serialize("argument") + Serializable.serialize(arg)
    }

    override def deserialize(input: Iterator[Char]): TestOption =
      Serializable.deserialize[String](input) match {
        case "exclude" => Serializable.deserialize[Exclude](input)
        case "argument" => Serializable.deserialize[Argument](input)
        case _ => sys.error("...")
      }
  }
}

final case class Exclude(val tests: Array[String]) extends TestOption

object Exclude {
  implicit val excludeSerializable: Serializable[Exclude] = new Serializable[Exclude] {
    override def serialize(v: Exclude): String = {
      Serializable.serialize(v.tests)
    }

    override def deserialize(input: Iterator[Char]): Exclude = {
      val tests = Serializable.deserialize[Array[String]](input)
      new Exclude(tests)
    }
  }
}

final case class Argument(val framework: Option[TestFramework], val args: Array[String])
    extends TestOption {
  def matches(clss: Class[_]): Boolean = {
    framework match {
      case None => true
      case Some(tf) => tf.implClasses.contains(clss.getName)
    }
  }
}

object Argument {
  implicit val argumentSerializable: Serializable[Argument] = new Serializable[Argument] {
    override def serialize(v: Argument): String = {
      val sFramework = Serializable.serialize(v.framework)
      val sArguments = Serializable.serialize(v.args)
      sFramework + sArguments
    }

    override def deserialize(input: Iterator[Char]): Argument = {
      val framework = Serializable.deserialize[Option[TestFramework]](input)
      val arguments = Serializable.deserialize[Array[String]](input)
      new Argument(framework, arguments)
    }
  }
}
