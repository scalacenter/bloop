package bloop.logging
import java.util.function.Supplier

abstract class Logger extends xsbti.Logger with sbt.testing.Logger {

  /** The name of the logger */
  def name: String

  /** Return true if logger is verbose, false otherwise. */
  def isVerbose: Boolean

  /** Return a logger that logs verbose and debug events. */
  def asVerbose: Logger

  /** Return a logger that doesn't log verbose and debug events. */
  def asDiscrete: Logger

  override def debug(msg: Supplier[String]): Unit = debug(msg.get())
  override def error(msg: Supplier[String]): Unit = error(msg.get())
  override def warn(msg: Supplier[String]): Unit = warn(msg.get())
  override def info(msg: Supplier[String]): Unit = info(msg.get)
  override def trace(exception: Supplier[Throwable]): Unit = trace(exception.get())

  def report(msg: String, t: Throwable): Unit = { error(msg); trace(t) }
}
