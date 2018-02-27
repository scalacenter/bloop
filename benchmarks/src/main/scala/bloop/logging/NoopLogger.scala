package bloop.logging

object NoopLogger extends AbstractLogger {
  override def name: String = "NoopLogger"
  override def ansiCodesSupported(): Boolean = true

  override def debug(msg: String): Unit = ()
  override def error(msg: String): Unit = ()
  override def warn(msg: String): Unit = ()
  override def trace(exception: Throwable): Unit = ()
  override def info(msg: String): Unit = ()
  override def verbose[T](op: => T): T = op
  override def isVerbose: Boolean = true
}
