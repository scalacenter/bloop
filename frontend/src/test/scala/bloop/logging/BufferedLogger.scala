package bloop.logging

import java.util.concurrent.ConcurrentLinkedDeque

final class BufferedLogger private (
    underlying: Logger,
    private val buffer: ConcurrentLinkedDeque[() => Unit]
) extends Logger {
  override def name: String = underlying.name
  override def ansiCodesSupported(): Boolean = underlying.ansiCodesSupported()
  override def logContext: LogContext = underlying.logContext

  override def printDebug(msg: String): Unit = buffer.addLast(() => underlying.printDebug(msg))
  override def debug(msg: String)(implicit ctx: LogContext): Unit =
    if (isVerbose && logContext.isEnabledFor(ctx)) buffer.addLast(() => printDebug(msg))

  override def error(msg: String): Unit = buffer.addLast(() => underlying.error(msg))
  override def warn(msg: String): Unit = buffer.addLast(() => underlying.warn(msg))
  override def trace(exception: Throwable): Unit = buffer.addLast(() => underlying.trace(exception))
  override def info(msg: String): Unit = buffer.addLast(() => underlying.info(msg))
  override def isVerbose: Boolean = underlying.isVerbose
  override def asVerbose: Logger = new BufferedLogger(underlying.asVerbose, buffer)
  override def asDiscrete: Logger = new BufferedLogger(underlying.asDiscrete, buffer)

  // Custom functions for buffered logger
  def clear(): Unit = buffer.clear()
  def flush(): Unit = {
    buffer.forEach(op => op.apply())
    buffer.clear()
  }

}

object BufferedLogger {
  def apply(underlying: Logger): BufferedLogger =
    new BufferedLogger(underlying, new ConcurrentLinkedDeque[() => Unit]())
}
