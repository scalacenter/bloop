package bloop.logging

import java.io.OutputStream

import org.openjdk.jmh.annotations.Benchmark

object BloopLoggerBenchmark {
  val logger = BloopLogger("benchmark")
  private val devnull: OutputStream = _ => ()
  BloopLogger.update(logger, devnull)
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
    BloopLoggerBenchmark.logger.debug("message")
  }

  @Benchmark
  def logVerbose(): Unit = {
    BloopLoggerBenchmark.logger.verbose(())
  }

}
