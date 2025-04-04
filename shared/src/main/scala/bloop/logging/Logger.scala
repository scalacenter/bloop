package bloop.logging

import java.io.PrintStream
import java.util.function.Supplier

import bloop.io.Environment

abstract class Logger extends xsbti.Logger with BaseSbtLogger {

  // Duplicate the standard output so that we get printlns from the compiler
  protected def redirectOutputToLogs(out: PrintStream) = {
    val baos = new FlushingOutputStream(info)
    val tee = new TeeOutputStream(out)
    tee.addListener(baos)
    System.setOut(tee)
  }

  /** The name of the logger */
  def name: String

  /** Return true if logger is verbose, false otherwise. */
  def isVerbose: Boolean

  /** Return a logger that logs verbose and debug events. */
  def asVerbose: Logger

  /** Return a logger that doesn't log verbose and debug events. */
  def asDiscrete: Logger

  /** Return a logger that doesn't log verbose and debug events. */
  def withOriginId(originId: Option[String]): Logger

  /** Context for debug logging. */
  def debugFilter: DebugFilter

  /** Defines a debug function that takes a message and a filter from its use site. */
  def debug(msg: String)(implicit ctx: DebugFilter): Unit

  override def debug(msg: Supplier[String]): Unit = printDebug(msg.get())
  override def error(msg: Supplier[String]): Unit = error(msg.get())
  override def warn(msg: Supplier[String]): Unit = warn(msg.get())
  override def info(msg: Supplier[String]): Unit = info(msg.get)
  override def trace(exception: Supplier[Throwable]): Unit = trace(exception.get())

  def error(msg: String, t: Throwable): Unit = {
    error(
      msg + Environment.lineSeparator + Logger.prettyPrintException(t)
    )
  }

  def handleCompilationEvent(): Unit = ()

  /** Display a message as a warning to user using `showMessage` in BSP-based loggers and `warn` otherwise. */
  def displayWarningToUser(msg: String): Unit = warn(msg)
}

/**
 * Define an intermediary trait that extends sbt's Logger and overrides
 * `debug` to help the linearized lookup find the `debug` that takes an
 * implicit instead of sbt's `debug` that doesn't. This technique enables
 * us avoid modifying the call-sites of `debug`.
 */
private[logging] trait BaseSbtLogger extends sbt.testing.Logger {
  private[logging] def printDebug(line: String): Unit
  override def debug(msg: String): Unit = printDebug(msg)
}

object Logger {
  def prettyPrintException(t: Throwable): String = {
    val sw = new java.io.StringWriter()
    val pw = new java.io.PrintWriter(sw)
    t.printStackTrace(pw)
    sw.toString()
  }
}
