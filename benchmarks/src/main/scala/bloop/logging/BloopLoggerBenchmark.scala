package bloop.logging

import java.io.PrintStream

import org.openjdk.jmh.annotations.Benchmark

object BloopLoggerBenchmark {
  private val devnull = new PrintStream(_ => ())
  val logger = BloopLogger.at("benchmark", devnull, devnull, false, LogContext.All)
}

class BloopLoggerBenchmark {

  @Benchmark
  def logInfo(): Unit = {
    BloopLoggerBenchmark.logger.info("message")
  }

  @Benchmark
  def logWarn(): Unit = {
    BloopLoggerBenchmark.logger.warn("message")
  }

  @Benchmark
  def logError(): Unit = {
    BloopLoggerBenchmark.logger.error("message")
  }

  @Benchmark
  def logDebug(): Unit = {
    // Debugs with `LogContext.All`
    BloopLoggerBenchmark.logger.debug("message")(LogContext.All)
  }

  @Benchmark
  def logDebugWithContext(): Unit = {
    implicit val logContext: LogContext = LogContext.All
    BloopLoggerBenchmark.logger.debug("message")(logContext)
  }
}
