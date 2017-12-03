package bloop.logging

import java.util.concurrent.ConcurrentLinkedDeque

class BufferedLogger(underlying: Logger) extends AbstractLogger {

  private[this] val buffer = new ConcurrentLinkedDeque[() => Unit]()

  override def name = underlying.name
  override def ansiCodesSupported() = underlying.ansiCodesSupported()

  def clear(): Unit = buffer.clear()
  def flush(): Unit = {
    buffer.forEach(op => op.apply())
    buffer.clear()
  }

  override def debug(msg: String) = buffer.addLast(() => underlying.debug(msg))
  override def error(msg: String) = buffer.addLast(() => underlying.error(msg))
  override def warn(msg: String) = buffer.addLast(() => underlying.warn(msg))
  override def trace(exception: Throwable) = buffer.addLast(() => underlying.trace(exception))
  override def info(msg: String) = buffer.addLast(() => underlying.info(msg))
  override def verbose[T](op: => T) = op

}
