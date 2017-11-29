package bloop.logging
import java.util.function.Supplier
import java.util.concurrent.ConcurrentLinkedDeque

class BufferedLogger(underlying: Logger) extends Logger(underlying) {

  private[this] val buffer = new ConcurrentLinkedDeque[() => Unit]()

  override def debug(msg: String) = buffer.addLast(() => underlying.debug(msg))
  override def debug(msg: Supplier[String]) = buffer.addLast(() => underlying.debug(msg))

  override def error(msg: String) = buffer.addLast(() => underlying.error(msg))
  override def error(msg: Supplier[String]) = buffer.addLast(() => underlying.error(msg))

  override def warn(msg: String) = buffer.addLast(() => underlying.warn(msg))
  override def warn(msg: Supplier[String]) = buffer.addLast(() => underlying.warn(msg))

  override def trace(exception: Throwable) = buffer.addLast(() => underlying.trace(exception))
  override def trace(exception: Supplier[Throwable]) =
    buffer.addLast(() => underlying.trace(exception))

  override def info(msg: String) = buffer.addLast(() => underlying.info(msg))
  override def info(msg: Supplier[String]) = buffer.addLast(() => underlying.info(msg.get))

  def clear(): Unit = buffer.clear()
  def flush(): Unit = {
    buffer.forEach(op => op.apply())
    buffer.clear()
  }
}
