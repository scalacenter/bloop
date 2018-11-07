package hello

import utest._

object UTestTest extends TestSuite {
  val tests = Tests {
    "Greetings are very personal" - {
      assert(Hello.greet("Martin") == "Hello, Martin!")
    }
  }
}
