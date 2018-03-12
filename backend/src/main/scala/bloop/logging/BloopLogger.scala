package bloop.logging

import java.io.{FileOutputStream, OutputStream, PrintStream}
import java.util.concurrent.atomic.AtomicInteger

import scala.Console.{CYAN, GREEN, RED, RESET, YELLOW}

import bloop.io.AbsolutePath

/**
 * Creates a logger that writes to the given streams.
 *
 * @param name The name of this logger.
 * @param out  The stream to use to write `INFO` and `WARN` level messages.
 * @param err  The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
 */
class BloopLogger(override val name: String, out: PrintStream, err: PrintStream)
    extends AbstractLogger {

  private val verboseCount: AtomicInteger = new AtomicInteger(0)

  override def ansiCodesSupported() = true
  override def debug(msg: String): Unit = if (isVerbose) print(msg, printDebug)
  override def error(msg: String): Unit = print(msg, printError)
  override def warn(msg: String): Unit = print(msg, printWarning)
  override def trace(exception: Throwable): Unit = {
    if (exception != null && isVerbose) {
      print(exception.toString(), printTrace)
      exception.getStackTrace.foreach(ste => print(ste.toString, printTrace))
    }
  }
  override def info(msg: String): Unit = print(msg, printInfo)

  override def verbose[T](op: => T): T = {
    val _ = verboseCount.incrementAndGet()
    try op
    finally { verboseCount.decrementAndGet(); () }
  }

  override def isVerbose: Boolean = verboseCount.get > 0

  private def print(msg: String, fn: String => Unit): Unit = {
    if (msg == null) ()
    else msg.lines.foreach(fn)
  }

  private def printInfo(line: String): Unit = {
    out.println(line)
  }

  private def printWarning(line: String): Unit = {
    out.println(s"${RESET}${YELLOW}[W]${RESET} $line")
  }

  private def printError(line: String): Unit = {
    err.println(s"${RESET}${RED}[E]${RESET} $line")
  }

  private def printTrace(line: String): Unit = {
    err.println(s"${RESET}${CYAN}[T]${RESET} $line")
  }

  private def printDebug(line: String): Unit = {
    err.println(s"${RESET}${GREEN}[D]${RESET} $line")
  }

}

object BloopLogger {

  /**
   * Instantiates a new `BloopLogger` using the specified streams.
   *
   * @param name The name of the logger.
   * @param out  The stream to use to write `INFO` and `WARN` level messages.
   * @param err  The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
   * @return A `BloopLogger` whose output will be written in the specified streams.
   */
  def at(name: String, out: PrintStream, err: PrintStream): BloopLogger =
    new BloopLogger(name, out, err)

  /**
   * Instantiates a new `BloopLogger` that writes to stdout and stderr.
   *
   * @param name The name of the logger.
   * @return A `BloopLogger` writing to stdout and stderr. Calling this method is equivalent to
   *         calling `at(name, System.out, System.err)`.
   */
  def default(name: String): BloopLogger = at(name, System.out, System.err)

}
