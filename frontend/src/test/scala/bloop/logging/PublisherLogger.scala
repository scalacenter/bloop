package bloop.logging

import java.util.concurrent.ConcurrentLinkedQueue

import monix.reactive.Observer

final class PublisherLogger(
    observer: Observer.Sync[(String, String)],
    debug: Boolean = false
) extends Logger {
  private[this] val messages = new ConcurrentLinkedQueue[(String, String)]
  override val name: String = "PublisherLogger"
  override val ansiCodesSupported: Boolean = false

  def add(key: String, value: String): Unit = {
    // Ignore clean screen to show all infos
    if (value == "\u001b[H\u001b[2J") ()
    else {
      if (debug) {
        println(s"[$key] $value")
      }

      messages.add((key, value))
      observer.onNext((key, value))
    }

    ()
  }

  import scala.collection.JavaConverters._
  def filterMessageByLabel(label: String): List[String] =
    messages.iterator.asScala.flatMap(lm => if (lm._1 == label) List(lm._2) else Nil).toList

  private def trace(msg: String): Unit = { add("trace", msg); () }
  override def debug(msg: String): Unit = { add("debug", msg); () }
  override def info(msg: String): Unit = { add("info", msg); () }
  override def error(msg: String): Unit = { add("error", msg); () }
  override def warn(msg: String): Unit = { add("warn", msg); () }
  override def trace(ex: Throwable): Unit = {
    ex.getStackTrace.foreach(ste => trace(ste.toString))
    Option(ex.getCause).foreach { cause =>
      trace("Caused by:")
      trace(cause)
    }
  }

  override def isVerbose: Boolean = true
  override def asVerbose: Logger = this
  override def asDiscrete: Logger = this
}
