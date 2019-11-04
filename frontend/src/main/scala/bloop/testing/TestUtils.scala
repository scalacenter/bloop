package bloop.testing

import bloop.logging.Logger
import sbt.testing.{
  Event,
  NestedSuiteSelector,
  NestedTestSelector,
  OptionalThrowable,
  Selector,
  SuiteSelector,
  TestSelector,
  TestWildcardSelector
}
import bloop.logging.DebugFilter

object TestUtils {
  private implicit val testFilter = DebugFilter.Test

  def printSelector(selector: Selector, logger: Logger): Option[String] = selector match {
    case c: TestSelector => Some(c.testName())
    case c: SuiteSelector => Some(c.toString)
    case c: NestedSuiteSelector => Some(c.suiteId())
    case c: TestWildcardSelector => Some(c.testWildcard())
    case c: NestedTestSelector => Some(c.testName())
    case _ =>
      logger.debug(s"Unexpected test selector $selector won't be pretty printed!")
      None
  }

  def printThrowable(opt: OptionalThrowable): Option[String] = {
    if (opt.isEmpty) None
    else Some(stripTestFrameworkSpecificInformation(opt.get().getMessage))
  }

  private val specs2Prefix = "java.lang.Exception: "
  private val utestPrefix = "utest.AssertionError: "
  private val scalaTestPrefix = "org.scalatest.exceptions.TestFailedException: "

  def stripTestFrameworkSpecificInformation(message: String): String =
    if (message.startsWith(scalaTestPrefix)) message.drop(scalaTestPrefix.length)
    else if (message.startsWith(specs2Prefix)) message.drop(specs2Prefix.length)
    else if (message.startsWith(utestPrefix)) message.drop(utestPrefix.length)
    else message
}
