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

  def optionalThrowableToString(opt: OptionalThrowable): String = {
    if(opt.isDefined) opt.get().getMessage
    else "" //TODO: Think if this is the best approach
  }
}
