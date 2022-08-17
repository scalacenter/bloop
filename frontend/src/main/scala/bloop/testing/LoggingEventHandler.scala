package bloop.testing

import scala.collection.mutable

import ch.epfl.scala.debugadapter.DebuggeeListener
import ch.epfl.scala.debugadapter.testing.TestSuiteEvent
import ch.epfl.scala.debugadapter.testing.TestSuiteEventHandler
import ch.epfl.scala.debugadapter.testing.TestUtils

import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.util.TimeFormat

import sbt.testing.Event
import sbt.testing.Status

trait BloopTestSuiteEventHandler extends TestSuiteEventHandler {
  def report(): Unit
}

class LoggingEventHandler(logger: Logger) extends BloopTestSuiteEventHandler {
  type SuiteName = String
  type TestName = String
  type FailureMessage = String

  private val failedStatuses = Set(Status.Error, Status.Canceled, Status.Failure)

  protected var suitesDuration = 0L
  protected var suitesPassed = 0
  protected var suitesAborted = 0
  protected val testsFailedBySuite: mutable.SortedMap[SuiteName, Map[TestName, FailureMessage]] =
    mutable.SortedMap.empty[SuiteName, Map[TestName, FailureMessage]]
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

    case results @ TestSuiteEvent.Results(testSuite, events) =>
      val testsTotal = events.length

      logger.info(s"Execution took ${TimeFormat.readableMillis(results.duration)}")
      val regularMetrics = List(
        testsTotal -> "tests",
        results.passed -> "passed",
        results.pending -> "pending",
        results.ignored -> "ignored",
        results.skipped -> "skipped"
      )

      // If test metrics
      val failureCount = results.failed + results.canceled + results.errors
      val failureMetrics =
        List(results.failed -> "failed", results.canceled -> "canceled", results.errors -> "errors")
      val testMetrics = formatMetrics(regularMetrics ++ failureMetrics)
      if (!testMetrics.isEmpty) logger.info(testMetrics)

      if (failureCount > 0) {
        val currentFailedTests = extractErrors(events, logger)
        val previousFailedTests = testsFailedBySuite.getOrElse(testSuite, Map.empty)
        testsFailedBySuite += testSuite -> (previousFailedTests ++ currentFailedTests)
      } else if (testsTotal <= 0) logger.info("No test suite was run")
      else {
        suitesPassed += 1
        logger.info(s"All tests in $testSuite passed")
      }

      logger.info("")
      suitesTotal += 1
      suitesDuration += results.duration

    case TestSuiteEvent.Done => ()
  }

  private def extractErrors(events: List[Event], logger: Logger) =
    events
      .filter(e => failedStatuses.contains(e.status()))
      .map { event =>
        val selectorOpt = TestUtils.printSelector(event.selector)
        if (selectorOpt.isEmpty) {
          logger.debug(s"Unexpected test selector ${event.selector} won't be pretty printed!")(
            DebugFilter.Test
          )
        }
        val key = selectorOpt.getOrElse("")
        val value = TestUtils.printThrowable(event.throwable()).getOrElse("")
        key -> value
      }
      .toMap

  override def report(): Unit = {
    // TODO: Shall we think of a better way to format this delimiter based on screen length?
    logger.info("===============================================")
    logger.info(s"Total duration: ${TimeFormat.readableMillis(suitesDuration)}")

    if (suitesTotal == 0) {
      logger.info(s"No test suites were run.")
    } else if (suitesPassed == suitesTotal) {
      logger.info(s"All $suitesPassed test suites passed.")
    } else {
      val metrics = List(
        suitesPassed -> "passed",
        testsFailedBySuite.size -> "failed",
        suitesAborted -> "aborted"
      )

      logger.info(formatMetrics(metrics))
      if (testsFailedBySuite.nonEmpty) {
        logger.info("")
        logger.info("Failed:")
        testsFailedBySuite.foreach {
          case (suiteName, failedTests) =>
            logger.info(s"- $suiteName:")
            val summary = failedTests.map {
              case (suiteName, failureMsg) =>
                TestSuiteEventHandler.formatError(suiteName, failureMsg, indentSize = 2)
            }
            summary.foreach(s => logger.info(s))
        }
      }
    }

    logger.info("===============================================")
  }
}

/**
 * Works just as an ordinary LoggingEventHandler, but
 * for TestSuiteEvent.Results extracts information about tests execution and send it to the DebuggeeListener.
 */
final class DebugLoggingEventHandler(logger: Logger, listener: DebuggeeListener)
    extends LoggingEventHandler(logger) {

  override def handle(event: TestSuiteEvent): Unit =
    event match {
      case results: TestSuiteEvent.Results =>
        val summary = TestSuiteEventHandler.summarizeResults(results)
        listener.testResult(summary)
        super.handle(event)
      case _ =>
        super.handle(event)
    }
}

object NoopEventHandler extends BloopTestSuiteEventHandler {
  override def handle(event: TestSuiteEvent): Unit = ()
  override def report(): Unit = ()
}
