package bloop.logging

import java.util.concurrent.ConcurrentLinkedQueue
import scala.collection.JavaConverters.asScalaIteratorConverter

final class RecordingLogger extends Logger {
  private[this] val messages = new ConcurrentLinkedQueue[(String, String)]

  def clear(): Unit = messages.clear()
  def getMessages(): List[(String, String)] = messages.iterator.asScala.toList.map {
    // Remove trailing '\r' so that we don't have to special case for Windows
    case (category, msg0) =>
      val msg = if (msg0 == null) "<null reference>" else msg0
      (category, msg.stripSuffix("\r"))
  }

  override val name: String = "TestRecordingLogger"
  override val ansiCodesSupported: Boolean = true

  override def debug(msg: String): Unit = { messages.add(("debug", msg)); () }
  override def info(msg: String): Unit = { messages.add(("info", msg)); () }
  override def error(msg: String): Unit = { messages.add(("error", msg)); () }
  override def warn(msg: String): Unit = { messages.add(("warn", msg)); () }
  override def trace(ex: Throwable): Unit = {
    ex.getStackTrace.foreach(ste => trace(ste.toString))
    Option(ex.getCause).foreach { cause =>
      trace("Caused by:")
      trace(cause)
    }
  }

  def serverInfo(msg: String): Unit = { messages.add(("server-info", msg)); () }
  def serverError(msg: String): Unit = { messages.add(("server-error", msg)); () }
  private def trace(msg: String): Unit = { messages.add(("trace", msg)); () }

  override def isVerbose: Boolean = true
  override def asVerbose: Logger = this
  override def asDiscrete: Logger = this
}
