package bloop.dap

import bloop.logging.Logger

class DebugServerLogger(logger: Logger) extends ch.epfl.scala.debugadapter.Logger {
  override def debug(msg: => String): Unit = logger.debug(() => msg)
  override def info(msg: => String): Unit = logger.info(() => msg)
  override def warn(msg: => String): Unit = logger.warn(() => msg)
  override def error(msg: => String): Unit = logger.error(() => msg)
  override def trace(t: => Throwable): Unit = logger.trace(() => t)
}
