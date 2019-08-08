package bloop.logging

import java.util.function.Supplier

abstract class Logger extends xsbti.Logger with BaseSbtLogger {

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

  def report(msg: String, t: Throwable): Unit = { error(msg); trace(t) }
  def handleCompilationEvent(event: CompilationEvent): Unit = ()

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
