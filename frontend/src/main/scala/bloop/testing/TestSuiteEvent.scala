package bloop.testing

import bloop.logging.Logger
import bloop.util.TimeFormat
import sbt.testing.{Event, Status}

import scala.collection.mutable

sealed trait TestSuiteEvent
object TestSuiteEvent {
  case object Done extends TestSuiteEvent
  case class Error(message: String) extends TestSuiteEvent
  case class Warn(message: String) extends TestSuiteEvent
  case class Info(message: String) extends TestSuiteEvent
  case class Debug(message: String) extends TestSuiteEvent
  case class Trace(throwable: Throwable) extends TestSuiteEvent

  /** @param testSuite Class name of test suite */
  case class Results(testSuite: String, events: List[Event]) extends TestSuiteEvent
}

trait TestSuiteEventHandler {
  def handle(testSuiteEvent: TestSuiteEvent): Unit
}

class LoggingEventHandler(logger: Logger) extends TestSuiteEventHandler {
  private var suitesDuration = 0L
  private var suitesPassed = 0
  private var suitesAborted = 0
  private val suitesFailed = mutable.ArrayBuffer.empty[String]
  private var suitesTotal = 0

  private def formatMetrics(metrics: List[(Int, String)]): String =
    metrics
      .filter(_._1 > 0)
      .map {
        case (value, metric) =>
          value + " " + metric
      }
      .mkString(", ")

  override def handle(event: TestSuiteEvent): Unit = event match {
    case TestSuiteEvent.Error(message) => logger.error(message)
    case TestSuiteEvent.Warn(message) => logger.warn(message)
    case TestSuiteEvent.Info(message) => logger.info(message)
    case TestSuiteEvent.Debug(message) => logger.debug(message)
    case TestSuiteEvent.Trace(throwable) =>
      logger.error("Test suite aborted.")
      logger.trace(throwable)

      suitesAborted += 1
      suitesTotal += 1

    case TestSuiteEvent.Results(testSuite, events) =>
      val testsTotal = events.length

      val duration = events.map(_.duration()).sum
      val passed = events.count(_.status() == Status.Success)
      val skipped = events.count(_.status() == Status.Skipped)
      val failed = events.count(_.status() == Status.Failure)
      val canceled = events.count(_.status() == Status.Canceled)
      val ignored = events.count(_.status() == Status.Ignored)
      val pending = events.count(_.status() == Status.Pending)
      val errors = events.count(_.status() == Status.Error)

      events
        .filter(_.throwable().isDefined)
        .map(_.throwable().get())
        .foreach(logger.trace)

      logger.info(s"Execution took ${TimeFormat.humanReadable(duration)}.")

      val regularMetrics = List(testsTotal -> "tests",
                                passed -> "passed",
                                pending -> "pending",
                                ignored -> "ignored",
                                skipped -> "skipped")

      val failureMetrics = List(failed -> "failed", canceled -> "canceled", errors -> "errors")

      logger.info(formatMetrics(regularMetrics ++ failureMetrics))

      if (failureMetrics.exists(_._1 != 0)) suitesFailed.append(testSuite)
      else {
        suitesPassed += 1
        logger.info(s"All tests passed.")
      }

      logger.info("")

      suitesTotal += 1
      suitesDuration += duration

    case TestSuiteEvent.Done =>
  }

  def report(): Unit = {
    logger.info("===============================================")
    logger.info(s"Total duration: ${TimeFormat.humanReadable(suitesDuration)}")

    if (suitesPassed == suitesTotal)
      logger.info(s"All $suitesPassed test suites passed.")
    else {
      val metrics =
        List(suitesPassed -> "passed", suitesFailed.length -> "failed", suitesAborted -> "aborted")
      logger.info(formatMetrics(metrics))

      if (suitesFailed.nonEmpty) {
        logger.info("")
        logger.info("Failed:")
        suitesFailed.foreach(suite => logger.info(s"- $suite"))
      }
    }

    logger.info("===============================================")
  }
}

case object NoopEventHandler extends TestSuiteEventHandler {
  override def handle(event: TestSuiteEvent): Unit = ()
}
