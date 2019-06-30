package bloop.testing

import bloop.logging.{DebugFilter, Logger}
import bloop.util.TimeFormat
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.endpoints.{Build, BuildTarget}
import sbt.testing.{Event, Selector, Status}

import scala.collection.mutable
import scala.meta.jsonrpc.JsonRpcClient
import scala.util.Try

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
  import bloop.testing.TestPrinter._
  private type SuiteName = String
  private type TestName = String
  private type FailureMessage = String
  protected var suitesDuration = 0L
  protected var suitesPassed = 0
  protected var suitesAborted = 0
  protected val testsFailedBySuite = mutable.SortedMap.empty[SuiteName, Map[TestName, FailureMessage]]
  protected var suitesTotal = 0

  protected def formatMetrics(metrics: List[(Int, String)]): String = {
    val relevant = metrics.iterator.filter(_._1 > 0)
    relevant.map { case (value, metric) => value + " " + metric }.mkString(", ")
  }

  override def handle(event: TestSuiteEvent): Unit = event match {
    case TestSuiteEvent.Error(message) => logger.error(message)
    case TestSuiteEvent.Warn(message) => logger.warn(message)
    case TestSuiteEvent.Info(message) => logger.info(message)
    case TestSuiteEvent.Debug(message) => logger.debug(message)(DebugFilter.Test)
    case TestSuiteEvent.Trace(throwable) =>
      logger.error("Test suite aborted")
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

      logger.info(s"Execution took ${TimeFormat.readableMillis(duration)}")
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

      val failedStatuses = Set(Status.Error, Status.Canceled, Status.Failure)
      if (failureCount > 0) {
        val thisSuiteFailedTests =
          events.filter(e => failedStatuses.contains(e.status()))
          .map(e => testSelectorToString(e.selector()) -> optionalThrowableToTestResult(e.throwable()))
          .toMap
        testsFailedBySuite += testSuite -> (testsFailedBySuite.getOrElse(testSuite, Map.empty) ++ thisSuiteFailedTests)
      }
      else if (testsTotal <= 0) logger.info("No test suite was run")
      else {
        suitesPassed += 1
        logger.info(s"All tests in $testSuite passed")
      }

      logger.info("")
      suitesTotal += 1
      suitesDuration += duration

    case TestSuiteEvent.Done => ()
  }

  override def report(): Unit = {
    // TODO: Shall we think of a better way to format this delimiter based on screen length?
    logger.info("===============================================")
    logger.info(s"Total duration: ${TimeFormat.readableMillis(suitesDuration)}")

    if (suitesTotal == 0) {
      logger.info(s"No test suites were run.")
    } else if (suitesPassed == suitesTotal) {
      logger.info(s"All $suitesPassed test suites passed.")
    } else {
      val metrics =
        List(suitesPassed -> "passed", testsFailedBySuite.size -> "failed", suitesAborted -> "aborted")
      logger.info(formatMetrics(metrics))

      if (testsFailedBySuite.nonEmpty) {
        logger.info("")
        logger.info("Failed:")
        testsFailedBySuite.foreach{case(suiteName, tests) =>
          logger.info(s"- $suiteName:")
          tests.foreach{ case(testName, failureMessage) =>
            logger.info(s"  * $testName - $failureMessage")
          }
        }
      }
    }

    logger.info("===============================================")
  }
}

final class BspLoggingEventHandler(id: BuildTargetIdentifier, logger: Logger, client: JsonRpcClient)
    extends LoggingEventHandler(logger) {
  implicit val client0: JsonRpcClient = client
  override def report(): Unit = {
    /*    val failed = suitesFailed.length
    val r = bsp.TestReport(id, None, suitesPassed, failed, 0, 0, 0, 0, Some(suitesDuration))
    Build.taskFinish
    BuildTarget.testReport.notify(r)
    ()*/
  }
}

object NoopEventHandler extends TestSuiteEventHandler {
  override def handle(event: TestSuiteEvent): Unit = ()
  override def report(): Unit = ()
}
