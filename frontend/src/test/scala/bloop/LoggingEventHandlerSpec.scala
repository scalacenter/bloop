package bloop

import bloop.logging.{DebugFilter, Logger, RecordingLogger}
import bloop.testing.{BaseSuite, LoggingEventHandler, TestSuiteEvent}
import sbt.testing.{Event, Fingerprint, OptionalThrowable, Selector, Status, TestSelector}

object LoggingEventHandlerSpec extends BaseSuite {
  test("logging and displaying short summary of successful run"){
    val logger = new RecordingLogger()
    val handler = new LoggingEventHandler(logger)

    
    handler.handle(TestSuiteEvent.Results("suite1", List(successfulEvent("suite1", "successful test"))))
    handler.handle(TestSuiteEvent.Results("suite1", List(successfulEvent("suite2", "successful test"))))
    handler.report()

    val expectedPassedMessage = "All 2 test suites passed."
    assert(logger.infos.contains(expectedPassedMessage))
  }

  test("logging and displaying short summary of run with failed test"){
    val logger = new RecordingLogger()
    val handler = new LoggingEventHandler(logger)

    handler.handle(TestSuiteEvent.Results("suite1", List(failedEvent("suite1", "failed.test1", "failure message1"))))
    handler.handle(TestSuiteEvent.Results("suite1", List(failedEvent("suite1", "failed.test2", "failure message2"))))
    handler.handle(TestSuiteEvent.Results("suite2", List(failedEvent("suite2", "failed.test3", "failure message1"))))

    handler.report()
    val expectedOutput =
      """
        |Failed:
        |- suite1:
        |  * failed.test1 - failure message1
        |  * failed.test2 - failure message2
        |- suite2:
        |  * failed.test3 - failure message1
      """.stripMargin.split("\n").toSeq.filter(_.trim.nonEmpty)

    val obtained = logger.infos

    assert(
      expectedOutput.forall(expectedLine => obtained.contains(expectedLine))
    )
  }
  def successfulEvent(suite: String, testName: String): Event = new Event {
    override def fullyQualifiedName(): String = ""

    override def fingerprint(): Fingerprint = ???

    override def selector(): Selector = new TestSelector(testName)

    override def status(): Status = Status.Success

    override def throwable(): OptionalThrowable = new OptionalThrowable()

    override def duration(): Long = 1l
  }

  def failedEvent(suite: String, testName: String, failedMessage: String) = new Event{
    override def fullyQualifiedName(): String = ""

    override def fingerprint(): Fingerprint = ???

    override def selector(): Selector = new TestSelector(testName)

    override def status(): Status = Status.Failure

    override def throwable(): OptionalThrowable = new OptionalThrowable(new Exception(failedMessage))

    override def duration(): Long = 1l
  }
}

