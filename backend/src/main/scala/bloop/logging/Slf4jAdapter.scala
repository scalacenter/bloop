package bloop.logging

import org.slf4j.{Marker, Logger => Slf4jLogger}

/**
 * Defines a slf4j-compliant logger wrapping Bloop logging utils.
 *
 * This slf4j interface is necessary to be compatible with third-party libraries
 * like lsp4s. It only intends to cover the basic functionality and it does not
 * support slf4j markers.
 *
 * @param logger A bloop logger.
 */
final class Slf4jAdapter[L <: Logger](logger: L) extends Slf4jLogger {
  def underlying: L = logger
  implicit val logContext: DebugFilter = logger.debugFilter

  override def getName: String = logger.name
  override def debug(msg: String): Unit = logger.debug(msg)
  override def debug(format: String, arg: scala.Any): Unit = logger.debug(arg.toString)

  override def debug(msg: String, t: Throwable): Unit = logger.debug(msg)
  override def debug(marker: Marker, msg: String): Unit = logger.debug(msg)
  override def debug(marker: Marker, msg: String, t: Throwable): Unit = logger.debug(msg)
  override def debug(marker: Marker, format: String, arg: scala.Any): Unit =
    logger.debug(arg.toString)

  override def debug(format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.debug(a.toString))
  override def debug(marker: Marker, format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.debug(a.toString))

  override def debug(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.debug(arg1.toString); logger.debug(arg2.toString)
  }

  override def debug(marker: Marker, format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.debug(arg1.toString); logger.debug(arg2.toString)
  }

  override def error(msg: String): Unit = logger.error(msg)
  override def error(format: String, arg: scala.Any): Unit = logger.error(arg.toString)
  override def error(msg: String, t: Throwable): Unit = logger.error(msg)
  override def error(marker: Marker, msg: String): Unit = logger.error(msg)
  override def error(marker: Marker, format: String, arg: scala.Any): Unit =
    logger.error(arg.toString)
  override def error(marker: Marker, msg: String, t: Throwable): Unit = logger.error(msg)

  override def error(marker: Marker, format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.error(arg1.toString); logger.error(arg2.toString)
  }

  override def error(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.error(arg1.toString); logger.error(arg2.toString)
  }

  override def error(format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.error(a.toString))
  override def error(marker: Marker, format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.error(a.toString))

  override def warn(msg: String): Unit = logger.warn(msg)
  override def warn(format: String, arg: scala.Any): Unit = logger.warn(arg.toString)
  override def warn(msg: String, t: Throwable): Unit = logger.warn(msg)
  override def warn(marker: Marker, msg: String): Unit = logger.warn(msg)
  override def warn(marker: Marker, msg: String, t: Throwable): Unit = logger.warn(msg)

  override def warn(marker: Marker, format: String, arg: scala.Any): Unit =
    logger.warn(arg.toString)

  override def warn(format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.warn(a.toString))
  override def warn(marker: Marker, format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.warn(a.toString))

  override def warn(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.warn(arg1.toString); logger.warn(arg2.toString)
  }

  override def warn(marker: Marker, format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.warn(arg1.toString); logger.warn(arg2.toString)
  }

  override def trace(msg: String): Unit = logger.debug(msg)
  override def trace(format: String, arg: scala.Any): Unit = logger.debug(arg.toString)
  override def trace(marker: Marker, msg: String): Unit = logger.debug(msg)
  override def trace(marker: Marker, format: String, arg: scala.Any): Unit =
    logger.debug(arg.toString)
  override def trace(format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.debug(a.toString))
  override def trace(marker: Marker, format: String, argArray: AnyRef*): Unit =
    argArray.foreach(a => logger.debug(a.toString))

  override def trace(msg: String, t: Throwable): Unit = {
    logger.debug(msg); logger.trace(t)
  }

  override def trace(marker: Marker, msg: String, t: Throwable): Unit = {
    logger.debug(msg); logger.trace(t)
  }

  override def trace(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.debug(arg1.toString); logger.debug(arg2.toString)
  }

  override def trace(marker: Marker, format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.debug(arg1.toString); logger.debug(arg2.toString)
  }

  override def isWarnEnabled: Boolean = true
  override def isWarnEnabled(marker: Marker): Boolean = true

  override def isInfoEnabled: Boolean = true
  override def isInfoEnabled(marker: Marker): Boolean = true

  override def isErrorEnabled: Boolean = true
  override def isErrorEnabled(marker: Marker): Boolean = true

  override def isTraceEnabled: Boolean = logger.isVerbose
  override def isTraceEnabled(marker: Marker): Boolean = logger.isVerbose

  override def isDebugEnabled: Boolean = logger.isVerbose
  override def isDebugEnabled(marker: Marker): Boolean = logger.isVerbose

  override def info(msg: String): Unit = logger.info(msg)
  override def info(format: String, arg: scala.Any): Unit = logger.info(arg.toString)

  override def info(format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.info(a.toString))
  override def info(marker: Marker, format: String, arguments: AnyRef*): Unit =
    arguments.foreach(a => logger.info(a.toString))

  override def info(msg: String, t: Throwable): Unit = logger.info(msg)
  override def info(marker: Marker, msg: String): Unit = logger.info(msg)
  override def info(marker: Marker, format: String, arg: scala.Any): Unit =
    logger.info(arg.toString)
  override def info(marker: Marker, msg: String, t: Throwable): Unit = logger.info(msg)

  override def info(format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.info(arg1.toString); logger.info(arg2.toString)
  }
  override def info(marker: Marker, format: String, arg1: scala.Any, arg2: scala.Any): Unit = {
    logger.info(arg1.toString); logger.info(arg2.toString)
  }
}
