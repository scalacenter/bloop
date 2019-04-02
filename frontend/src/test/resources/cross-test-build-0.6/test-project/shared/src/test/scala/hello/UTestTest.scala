package hello

import utest._

object UTestTest extends TestSuite {
  import utest.framework.Formatter
  override def utestFormatter: Formatter = new Formatter {
    override def formatColor = false
  }
  val tests = Tests {
    "Greetings are very personal" - {
      assert(Hello.greet("Martin") == "Hello, Martin!")
    }
  }
}
