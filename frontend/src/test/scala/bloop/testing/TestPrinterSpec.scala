package bloop.testing

object TestPrinterSpec extends BaseSuite {
  test("stripping parts of message created by test framework"){
    val testCases = List(
      ("org.scalatest.exceptions.TestFailedException: 8 did not equal 3", "8 did not equal 3"),
      ("java.lang.Exception: 9 != 3", "9 != 3"),
      ("utest.AssertionError: assert(cube(2) == 3)", "assert(cube(2) == 3)"),
      ("some other java.lang.Exception: 9 != 3", "some other java.lang.Exception: 9 != 3")
    )

    testCases.foreach{ case(testFrameworkMessage, expectedTruncatedMessage) =>
      val bool = TestPrinter.stripTestFrameworkSpecificInformation(testFrameworkMessage) == expectedTruncatedMessage
      assert(bool)
    }
  }
}
