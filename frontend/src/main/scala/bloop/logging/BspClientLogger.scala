package bloop.logging

/** Creates a logger that extends scribe's `LoggerSupport` for BSP's `LanguageClient`. */
final class BspClientLogger[L <: Logger](val underlying: L) extends Logger with ScribeAdapter {

  override val logContext: LogContext = underlying.logContext
  override val name: String = underlying.name

  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger = new BspClientLogger(underlying.asDiscrete)
  override def asVerbose: Logger = new BspClientLogger(underlying.asVerbose)

  override def printDebug(msg: String): Unit = underlying.printDebug(msg)
  override def debug(msg: String)(implicit ctx: LogContext): Unit =
    if (logContext.isEnabledFor(ctx)) printDebug(msg)

  override def ansiCodesSupported: Boolean = underlying.ansiCodesSupported()
  override def trace(t: Throwable): Unit = underlying.trace(t)
  override def error(msg: String): Unit = underlying.error(msg)
  override def warn(msg: String): Unit = underlying.warn(msg)
  override def info(msg: String): Unit = underlying.info(msg)
}
