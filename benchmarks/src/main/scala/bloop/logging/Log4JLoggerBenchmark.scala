package bloop.logging

import org.openjdk.jmh.annotations.Benchmark

object Log4JLoggerBenchmark {
  val logger = new Log4JLogger("benchmark")
}
class Log4JLoggerBenchmark {

  @Benchmark
  def logInfo(): Unit = {
    Log4JLoggerBenchmark.logger.info("message")
  }

  @Benchmark
  def logWarn(): Unit = {
    Log4JLoggerBenchmark.logger.warn("message")
  }

  @Benchmark
  def logError(): Unit = {
    Log4JLoggerBenchmark.logger.error("message")
  }

  @Benchmark
  def logDebug(): Unit = {
    Log4JLoggerBenchmark.logger.debug("message")
  }

  @Benchmark
  def logVerbose(): Unit = {
    Log4JLoggerBenchmark.logger.verbose(())
  }

}
