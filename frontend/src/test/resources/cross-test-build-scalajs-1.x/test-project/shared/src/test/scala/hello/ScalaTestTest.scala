package hello

import org.scalatest._

class ScalaTestTest extends FlatSpec with Matchers {
  "A greeting" should "be very personal" in {
    Hello.greet("Martin") shouldBe "Hello, Martin!"
  }
}
