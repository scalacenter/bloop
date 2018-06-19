package bloop.logging

import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger

import scala.Console.{CYAN, GREEN, RED, RESET, YELLOW}
import com.martiansoftware.nailgun.NGCommunicator

/**
 * Creates a logger that writes to the given streams.
 *
 * @param name The name of this logger.
 * @param out  The stream to use to write `INFO` and `WARN` level messages.
 * @param err  The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
 * @param verboseFilter The function that select messages written to `DEBUG` regardless of `isVerbose`.
 */
final class BloopLogger(
    override val name: String,
    out: PrintStream,
    err: PrintStream,
    verboseFilter: String => Boolean,
    private val debugCount: Int
) extends Logger {

  override def ansiCodesSupported() = true
  override def debug(msg: String): Unit = if (isVerbose || verboseFilter(msg)) print(msg, printDebug)
  override def error(msg: String): Unit = print(msg, printError)
  override def warn(msg: String): Unit = print(msg, printWarning)
  override def trace(exception: Throwable): Unit = trace("", exception)
  override def info(msg: String): Unit = print(msg, printInfo)

  override def isVerbose: Boolean = debugCount > 0
  override def asDiscrete: Logger =
    if (debugCount > 0) new BloopLogger(name, out, err, verboseFilter, debugCount - 1) else this
  override def asVerbose: Logger = new BloopLogger(name, out, err, verboseFilter, debugCount + 1)
  def withFilter(filter: String => Boolean): Logger = new BloopLogger(name, out, err, filter, debugCount)

  @scala.annotation.tailrec
  private def trace(prefix: String, exception: Throwable): Unit = {
    if (isVerbose) {
      print(prefix + exception.toString(), printTrace)
      exception.getStackTrace.foreach(ste => print("\t" + ste.toString, printTrace))

      val cause = exception.getCause
      if (cause != null) trace("Caused by: ", cause)
    }
  }

  private def print(msg: String, fn: String => Unit): Unit = {
    val lines = msg.split("\\r?\\n", -1)
    lines.foreach(fn)
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
   * @param name      The name of the logger.
   * @param out       The stream to use to write `INFO` and `WARN` level messages.
   * @param err       The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
   * @param isVerbose Tells whether the logger is verbose or not.
   * @param verboseFilter The function that select messages written to `DEBUG` regardless of `isVerbose`.
   * @return A `BloopLogger` whose output will be written in the specified streams.
   */
  def at(name: String, out: PrintStream, err: PrintStream, isVerbose: Boolean, verboseFilter: String => Boolean): BloopLogger =
    new BloopLogger(name, out, err, verboseFilter, if (isVerbose) 1 else 0)

  /**
   * Instantiates a new `BloopLogger` using the specified streams.
   *
   * @param name The name of the logger.
   * @param out  The stream to use to write `INFO` and `WARN` level messages.
   * @param err  The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
   * @param verboseFilter The function that select messages written to `DEBUG` regardless of `isVerbose`.
   * @return A `BloopLogger` whose output will be written in the specified streams.
   */
  def at(name: String, out: PrintStream, err: PrintStream, verboseFilter: String => Boolean): BloopLogger =
    at(name, out, err, false, verboseFilter)

  /**
   * Instantiates a new `BloopLogger` that writes to stdout and stderr.
   *
   * @param name The name of the logger.
   * @return A `BloopLogger` writing to stdout and stderr. Calling this method is equivalent to
   *         calling `at(name, System.out, System.err)`.
   */
  def default(name: String): BloopLogger = at(name, System.out, System.err, msg => false)

}
