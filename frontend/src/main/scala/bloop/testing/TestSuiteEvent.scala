package bloop.testing

import bloop.logging.Logger
import bloop.util.TimeFormat
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.endpoints.BuildTarget
import sbt.testing.{Event, Status}

import scala.collection.mutable
import scala.meta.jsonrpc.JsonRpcClient

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
  def report(): Unit
}

class LoggingEventHandler(logger: Logger) extends TestSuiteEventHandler {
  protected var suitesDuration = 0L
  protected var suitesPassed = 0
  protected var suitesAborted = 0
  protected val suitesFailed = mutable.ArrayBuffer.empty[String]
  protected var suitesTotal = 0

  protected def formatMetrics(metrics: List[(Int, String)]): String = {
    val relevant = metrics.iterator.filter(_._1 > 0)
    relevant.map { case (value, metric) => value + " " + metric }.mkString(", ")
  }

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

      // Log any exception that may have happened
      events.iterator
        .filter(_.throwable().isDefined)
        .map(_.throwable().get())
        .foreach(logger.trace)

      logger.info(s"Execution took ${TimeFormat.printUntilHours(duration)}.")
      val regularMetrics = List(
        testsTotal -> "tests",
        passed -> "passed",
        pending -> "pending",
        ignored -> "ignored",
        skipped -> "skipped"
      )

      // If test metrics
      val failureCount = failed + canceled + errors
      val failureMetrics = List(failed -> "failed", canceled -> "canceled", errors -> "errors")
      val testMetrics = formatMetrics(regularMetrics ++ failureMetrics)
      if (!testMetrics.isEmpty) logger.info(testMetrics)

      if (failureCount > 0) suitesFailed.append(testSuite)
      else {
        if (testsTotal <= 0) logger.info("No test suite was run.")
        else {
          suitesPassed += 1
          logger.info(s"All tests in ${testSuite} passed.")
        }
      }

      logger.info("")
      suitesTotal += 1
      suitesDuration += duration

    case TestSuiteEvent.Done => ()
  }

  override def report(): Unit = {
    // TODO: Shall we think of a better way to format this delimiter based on screen length?
    logger.info("===============================================")
    logger.info(s"Total duration: ${TimeFormat.printUntilHours(suitesDuration)}")

    if (suitesTotal == 0) {
      logger.info(s"No test suites were run.")
    } else if (suitesPassed == suitesTotal) {
      logger.info(s"All $suitesPassed test suites passed.")
    } else {
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

final class BspLoggingEventHandler(id: BuildTargetIdentifier, logger: Logger, client: JsonRpcClient)
    extends LoggingEventHandler(logger) {
  implicit val client0: JsonRpcClient = client
  override def report(): Unit = {
    val failed = suitesFailed.length
    val r = bsp.TestReport(id, None, suitesPassed, failed, 0, 0, 0, 0, Some(suitesDuration))
    BuildTarget.testReport.notify(r)
    ()
  }
}

object NoopEventHandler extends TestSuiteEventHandler {
  override def handle(event: TestSuiteEvent): Unit = ()
  override def report(): Unit = ()
}
