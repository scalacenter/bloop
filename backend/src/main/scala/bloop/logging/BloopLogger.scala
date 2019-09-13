package bloop.logging

import java.io.PrintStream

import scala.Console.{CYAN, GREEN, RED, RESET, YELLOW}

/**
 * Creates a logger that writes to the given streams.
 *
 * @param name        The name of this logger.
 * @param out         The stream to use to write `INFO` and `WARN` level messages.
 * @param err         The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
 * @param colorOutput Print with or without color.
 * @param debugFilter  Narrows logs to specified context.
 */
final class BloopLogger(
    override val name: String,
    out: PrintStream,
    err: PrintStream,
    private val debugCount: Int,
    colorOutput: Boolean,
    val debugFilter: DebugFilter,
    originId: Option[String]
) extends Logger {
  override def ansiCodesSupported() = true
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit =
    if (isVerbose && debugFilter.isEnabledFor(ctx)) print(msg, printDebug)
  override def error(msg: String): Unit = print(msg, printError)
  override def warn(msg: String): Unit = print(msg, printWarning)
  override def trace(exception: Throwable): Unit = trace("", exception)
  override def info(msg: String): Unit = print(msg, printInfo)

  override def asDiscrete: Logger = {
    if (debugCount <= 0) this
    else new BloopLogger(name, out, err, debugCount - 1, colorOutput, debugFilter, originId)
  }

  override def isVerbose: Boolean = debugCount > 0
  override def asVerbose: Logger = {
    new BloopLogger(name, out, err, debugCount + 1, colorOutput, debugFilter, originId)
  }

  override def withOriginId(originId: Option[String]): Logger =
    new BloopLogger(name, out, err, debugCount, colorOutput, debugFilter, originId)

  @scala.annotation.tailrec
  private def trace(prefix: String, exception: Throwable): Unit = {
    if (isVerbose) {
      print(prefix + exception.toString, printTrace)
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

  private def colored(color: String, msg: String): String = {
    if (colorOutput)
      s"${RESET}${color}$msg${RESET}"
    else
      msg
  }

  private def printWarning(line: String): Unit = {
    out.println(s"${colored(YELLOW, "[W]")} $line")
  }

  private def printError(line: String): Unit = {
    err.println(s"${colored(RED, "[E]")} $line")
  }

  private def printTrace(line: String): Unit = {
    err.println(s"${colored(CYAN, "[T]")} $line")
  }

  override private[logging] def printDebug(line: String): Unit = {
    if (!isVerbose) ()
    else err.println(s"${colored(GREEN, "[D]")} $line")
  }
}

object BloopLogger {

  /**
   * Instantiates a new `BloopLogger` using the specified streams.
   *
   * @param name       The name of the logger.
   * @param out        The stream to use to write `INFO` and `WARN` level messages.
   * @param err        The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
   * @param isVerbose  Tells whether the logger is verbose or not.
   * @param filter     Filters that apply to all debug messages.
   * @return A `BloopLogger` whose output will be written in the specified streams.
   */
  def at(
      name: String,
      out: PrintStream,
      err: PrintStream,
      isVerbose: Boolean,
      colorOutput: Boolean,
      filter: DebugFilter
  ): BloopLogger = {
    new BloopLogger(name, out, err, if (isVerbose) 1 else 0, colorOutput, filter, None)
  }

  /**
   * Instantiates a new `BloopLogger` using the specified streams.
   *
   * @param name       The name of the logger.
   * @param out        The stream to use to write `INFO` and `WARN` level messages.
   * @param err        The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
   * @param filter     Filters that apply to all debug messages.
   * @return A `BloopLogger` whose output will be written in the specified streams.
   */
  def at(
      name: String,
      out: PrintStream,
      err: PrintStream,
      colorOutput: Boolean,
      filter: DebugFilter
  ): BloopLogger = at(name, out, err, false, colorOutput, filter)

  /**
   * Instantiates a new `BloopLogger` that writes to stdout and stderr.
   *
   * @param name The name of the logger.
   * @return A `BloopLogger` writing to stdout and stderr. Calling this method is equivalent to
   *         calling `at(name, System.out, System.err)`.
   */
  def default(name: String): BloopLogger =
    at(name, System.out, System.err, false, DebugFilter.All)
}
