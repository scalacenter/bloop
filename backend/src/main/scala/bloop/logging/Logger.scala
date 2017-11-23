package bloop.logging

import scala.compat.Platform.EOL
import java.util.function.Supplier

import org.apache.logging.log4j
import org.apache.logging.log4j.LogManager

class Logger(logger: log4j.Logger) extends xsbti.Logger {
  def this(name: String) = this(LogManager.getLogger(name))
  def this() = this("bloop")

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

  def quietIfSuccess[T](op: BufferedLogger => T): T = {
    val bufferedLogger = new BufferedLogger(this)
    try op(bufferedLogger)
    catch {
      case ex: Throwable =>
        bufferedLogger.flush()
        throw ex
    }
  }
}
