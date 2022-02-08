package bloop.testing

import cats.data.NonEmptyList
import bloop.bsp.ScalaTestSuiteSelection
import TestSuiteSelection._

/**
 * https://github.com/sbt/junit-interface/pull/108#issuecomment-1002225661
 */
final case class TestSuiteSelection(private val suites: Map[SuiteName, NonEmptyList[TestName]]) {
  def get(suiteName: SuiteName): Option[NonEmptyList[TestName]] = suites.get(suiteName)
}

object TestSuiteSelection {
  type SuiteName = String
  type TestName = String

  val empty: TestSuiteSelection = new TestSuiteSelection(Map.empty)

  def apply(selections: List[ScalaTestSuiteSelection]): TestSuiteSelection = {
    val testSelection = selections.collect {
      case ScalaTestSuiteSelection(className, head :: tail) =>
        (className, NonEmptyList(head, tail))
    }.toMap
    TestSuiteSelection(testSelection)
  }
}
