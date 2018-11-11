package hello

import utest._

object EternalUTest extends TestSuite {
  val tests = Tests {
    "This test never ends" - {
      while (true) ()
    }
  }
}
