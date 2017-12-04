package bloop.logging

import org.apache.logging.log4j
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.config.Configurator

class Log4JLogger(override val name: String) extends AbstractLogger {
  def this(logger: Logger) = this(logger.name)

  private val logger: log4j.Logger = LogManager.getLogger(name)

  override def ansiCodesSupported() = true

  override def debug(msg: String): Unit = msg.lines.foreach(logger.debug)
  override def error(msg: String): Unit = msg.lines.foreach(logger.error)
  override def warn(msg: String): Unit = msg.lines.foreach(logger.warn)
  override def trace(exception: Throwable): Unit = logger.trace(exception)

  override def info(msg: String): Unit = msg.lines.foreach(logger.info)

  override def verbose[T](op: => T): T = {
    val initialLevel = LogManager.getRootLogger.getLevel
    Configurator.setRootLevel(Level.DEBUG)
    try op
    finally Configurator.setRootLevel(initialLevel)
  }
}
