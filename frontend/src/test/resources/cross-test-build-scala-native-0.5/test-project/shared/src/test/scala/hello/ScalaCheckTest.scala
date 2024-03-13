package hello

import org.scalacheck.Properties
import org.scalacheck.Prop.forAll

object ScalaCheckTest extends Properties("Greeting") {

  property("is personal") = forAll { (name: String) =>
    Hello.greet(name) == s"Hello, $name!"
  }
}
