package bloop.logging

import java.util.function.Supplier

import org.apache.logging.log4j
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.config.Configurator

object Logger {
  val name = "bloop"
  def get = new Logger
}
class Logger private (private val logger: log4j.Logger)
    extends xsbti.Logger
    with sbt.testing.Logger {
  private def this() = this(LogManager.getLogger(Logger.name))
  def this(logger: Logger) = this(logger.logger)

  def name: String = logger.getName()
  override def ansiCodesSupported() = true

  def debug(msg: String): Unit = msg.lines.foreach(logger.debug)
  override def debug(msg: Supplier[String]): Unit = debug(msg.get())

  def error(msg: String): Unit = msg.lines.foreach(logger.error)
  override def error(msg: Supplier[String]): Unit = error(msg.get())

  def warn(msg: String): Unit = msg.lines.foreach(logger.warn)
  override def warn(msg: Supplier[String]): Unit = warn(msg.get())

  def trace(exception: Throwable): Unit = logger.trace(exception)
  override def trace(exception: Supplier[Throwable]): Unit =
    logger.trace(exception.get())

  def info(msg: String): Unit = msg.lines.foreach(logger.info)
  override def info(msg: Supplier[String]): Unit = info(msg.get)

  def quietIfError[T](op: BufferedLogger => T): T = verbose {
    val bufferedLogger = new BufferedLogger(this)
    try op(bufferedLogger)
    catch { case ex: Throwable => bufferedLogger.clear(); throw ex }
  }

  def quietIfSuccess[T](op: BufferedLogger => T): T = verbose {
    val bufferedLogger = new BufferedLogger(this)
    try op(bufferedLogger)
    catch { case ex: Throwable => bufferedLogger.flush(); throw ex }
  }

  def verboseIf[T](cond: Boolean)(op: => T): T =
    if (cond) verbose(op)
    else op

  def verbose[T](op: => T): T = {
    val initialLevel = LogManager.getRootLogger.getLevel
    Configurator.setRootLevel(Level.DEBUG)
    try op
    finally Configurator.setRootLevel(initialLevel)
  }
}
