package bloop.testing

import sbt.testing.{NestedSuiteSelector, NestedTestSelector, OptionalThrowable, Selector, SuiteSelector, TestSelector, TestWildcardSelector}

private [testing] object TestPrinter {
  def testSelectorToString(selector: Selector): String = selector match {
    case c: TestSelector => c.testName()
    case c: SuiteSelector => c.toString
    case c: NestedSuiteSelector => c.suiteId()
    case c: TestWildcardSelector => c.testWildcard()
    case c: NestedTestSelector => c.suiteId()
    case _ => ??? //TODO: think what to do if sbt adds new subclass
  }

  def optionalThrowableToTestResult(opt: OptionalThrowable): String = {
    if(opt.isDefined) stripTestFrameworkSpecificInformation(opt.get().getMessage)
    else "" //TODO: Think if this is the best approach
  }

  private val scalaTestPrefix = "org.scalatest.exceptions.TestFailedException: "
  private val specs2Prefix = "java.lang.Exception: "
  private val utestPrefix = "utest.AssertionError: "

  def stripTestFrameworkSpecificInformation(message: String): String =
    if(message.startsWith(scalaTestPrefix)) message.drop(scalaTestPrefix.length)
    else if(message.startsWith(specs2Prefix)) message.drop(specs2Prefix.length)
    else if(message.startsWith(utestPrefix)) message.drop(utestPrefix.length)
    else message
}
