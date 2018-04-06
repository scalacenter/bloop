package bloop.logging

object NoopLogger extends Logger {
  override def name: String = "NoopLogger"
  override def ansiCodesSupported(): Boolean = false

  override def debug(msg: String): Unit = ()
  override def error(msg: String): Unit = ()
  override def warn(msg: String): Unit = ()
  override def trace(exception: Throwable): Unit = ()
  override def info(msg: String): Unit = ()
  override def isVerbose: Boolean = false
  override def asDiscrete: Logger = this
  override def asVerbose: Logger = this
}
