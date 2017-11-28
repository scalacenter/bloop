package bloop.logging

import scala.compat.Platform.EOL
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

  def progress(msg: String): Unit = logger.info(msg)

  def debug(msg: String): Unit = logger.debug(msg + EOL)
  override def debug(msg: Supplier[String]): Unit =
    msg.get().lines.foreach(l => logger.debug(l + EOL))

  def error(msg: String): Unit = logger.error(msg + EOL)
  override def error(msg: Supplier[String]): Unit =
    msg.get().lines.foreach(l => logger.error(l + EOL))

  def warn(msg: String): Unit = logger.warn(msg + EOL)
  override def warn(msg: Supplier[String]): Unit =
    msg.get().lines.foreach(l => logger.warn(l + EOL))

  def trace(exception: Throwable): Unit = logger.trace(exception)
  override def trace(exception: Supplier[Throwable]): Unit =
    logger.trace(exception.get())

  def info(msg: String): Unit = logger.info(msg + EOL)
  override def info(msg: Supplier[String]): Unit =
    msg.get().lines.foreach(l => logger.info(l + EOL))

  def quietIfSuccess[T](op: BufferedLogger => T): T = verbose {
    val bufferedLogger = new BufferedLogger(this)
    try op(bufferedLogger)
    catch {
      case ex: Throwable =>
        bufferedLogger.flush()
        throw ex
    }
  }

  override def ansiCodesSupported() = true

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
