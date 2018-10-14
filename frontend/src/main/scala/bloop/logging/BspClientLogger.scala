package bloop.logging

import scribe.LogRecord

/** Creates a logger that extends scribe's `LoggerSupport` for BSP's `LanguageClient`. */
final class BspClientLogger[L <: Logger](val underlying: L)
    extends Logger
    with scribe.LoggerSupport {

  override val logContext: LogContext = underlying.logContext
  override val name: String = underlying.name

  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger = new BspClientLogger(underlying.asDiscrete)
  override def asVerbose: Logger = new BspClientLogger(underlying.asVerbose)

  override def ansiCodesSupported: Boolean = underlying.ansiCodesSupported()
  override def debug(msg: String): Unit = underlying.debug(msg)
  override def trace(t: Throwable): Unit = underlying.trace(t)
  override def error(msg: String): Unit = underlying.error(msg)
  override def warn(msg: String): Unit = underlying.warn(msg)
  override def info(msg: String): Unit = underlying.info(msg)

  override def log[M](record: LogRecord[M]): Unit = {
    import scribe.Level
    val msg = record.message
    record.level match {
      case Level.Info => info(msg)
      case Level.Error => error(msg)
      case Level.Warn => warn(msg)
      case Level.Debug => debug(msg)
      case Level.Trace =>
        record.throwable match {
          case Some(t) => trace(t)
          case None => debug(record.message)
        }
    }
  }
}
