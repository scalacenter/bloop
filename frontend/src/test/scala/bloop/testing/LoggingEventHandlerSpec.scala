package bloop.testing

import ch.epfl.scala.debugadapter.testing.TestSuiteEvent

import bloop.logging.RecordingLogger

import sbt.testing.Event
import sbt.testing.Fingerprint
import sbt.testing.OptionalThrowable
import sbt.testing.Selector
import sbt.testing.Status
import sbt.testing.TestSelector

object LoggingEventHandlerSpec extends BaseSuite {
  test("logs and displays short summary of successful run") {
    val logger = new RecordingLogger()
    val handler = new LoggingEventHandler(logger)

    handler.handle(
      TestSuiteEvent.Results("suite1", List(successfulEvent("suite1", "successful test")))
    )
    handler.handle(
      TestSuiteEvent.Results("suite2", List(successfulEvent("suite2", "successful test")))
    )

    handler.report()
    assertNoDiff(
      logger.renderTimeInsensitiveInfos,
      """|Execution took ???
         |1 tests, 1 passed
         |All tests in suite1 passed
         |
         |Execution took ???
         |1 tests, 1 passed
         |All tests in suite2 passed
         |
         |===============================================
         |Total duration: ???
         |All 2 test suites passed.
         |===============================================
         |""".stripMargin
    )
  }

  test("logs and displays short summary of run with failed test") {
    val logger = new RecordingLogger()
    val handler = new LoggingEventHandler(logger)

    handler.handle(
      TestSuiteEvent
        .Results("suite1", List(failedEvent("suite1", "failed.test1", "failure message1")))
    )
    handler.handle(
      TestSuiteEvent
        .Results("suite1", List(failedEvent("suite1", "failed.test2", "failure message2")))
    )
    handler.handle(
      TestSuiteEvent
        .Results("suite2", List(failedEvent("suite2", "failed.test3", "failure message1")))
    )

    handler.report()
    assertNoDiff(
      logger.renderTimeInsensitiveInfos,
      """|Execution took ???
         |1 tests, 1 failed
         |
         |Execution took ???
         |1 tests, 1 failed
         |
         |Execution took ???
         |1 tests, 1 failed
         |
         |===============================================
         |Total duration: ???
         |2 failed
         |
         |Failed:
         |- suite1:
         | * failed.test1 - failure message1
         | * failed.test2 - failure message2
         |- suite2:
         | * failed.test3 - failure message1
         |===============================================
         |""".stripMargin
    )
  }
  private def successfulEvent(suite: String, testName: String): Event = new Event {
    override def fullyQualifiedName(): String = suite
    override def fingerprint(): Fingerprint = ???
    override def selector(): Selector = new TestSelector(testName)
    override def status(): Status = Status.Success
    override def duration(): Long = 1L
    override def throwable(): OptionalThrowable = new OptionalThrowable()
  }

  def failedEvent(suite: String, testName: String, failedMessage: String): Event = new Event {
    override def fullyQualifiedName(): String = suite
    override def fingerprint(): Fingerprint = ???
    override def selector(): Selector = new TestSelector(testName)
    override def status(): Status = Status.Failure
    override def duration(): Long = 1L
    override def throwable(): OptionalThrowable =
      new OptionalThrowable(new Exception(failedMessage))
  }
}
