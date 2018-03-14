package bloop.logging

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters.asScalaIteratorConverter

class RecordingLogger extends AbstractLogger {
  private[this] val messages = new ConcurrentLinkedQueue[(String, String)]

  def clear(): Unit = messages.clear()
  def getMessages(): List[(String, String)] = messages.iterator.asScala.toList

  override val name: String = "RecordingLogger"
  override val ansiCodesSupported: Boolean = true

  override def verbose[T](op: => T): T = op
  override def debug(msg: String): Unit = { messages.add(("debug", msg)); () }
  override def info(msg: String): Unit = { messages.add(("info", msg)); () }
  def serverInfo(msg: String): Unit = { messages.add(("server-info", msg)); () }
  override def error(msg: String): Unit = { messages.add(("error", msg)); () }
  def serverError(msg: String): Unit = { messages.add(("server-error", msg)); () }
  override def warn(msg: String): Unit = { messages.add(("warn", msg)); () }
  private def trace(msg: String): Unit = { messages.add(("trace", msg)); () }
  override def trace(ex: Throwable): Unit = {
    ex.getStackTrace.foreach(ste => trace(ste.toString))
    Option(ex.getCause).foreach { cause =>
      trace("Caused by:")
      trace(cause)
    }
  }

  override def isVerbose: Boolean = true
}
