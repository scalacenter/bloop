package hello

import org.scalatest._

class WritingTest extends FlatSpec with Matchers {
  "A test" should "be able to print stuff" in {
    (1 to 10).foreach(_ => println("message"))
  }
}
