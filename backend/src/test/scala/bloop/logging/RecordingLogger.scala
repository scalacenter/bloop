package bloop.logging

import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.JavaConverters.asScalaIteratorConverter

final class RecordingLogger(
    debug: Boolean = false,
    debugOut: Option[PrintStream] = None,
    val logContext: LogContext = LogContext.All
) extends Logger {
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

  def add(key: String, value: String): Unit = {
    if (debug) {
      debugOut match {
        case Some(o) => o.println(s"[$key] $value")
        case None => println(s"[$key] $value")
      }
    }

    messages.add((key, value))
    ()
  }

  override def printDebug(msg: String): Unit = add("debug", msg)
  override def debug(msg: String)(implicit ctx: LogContext): Unit =
    if (logContext.isEnabledFor(ctx)) add("debug", msg)

  override def info(msg: String): Unit = add("info", msg)
  override def error(msg: String): Unit = add("error", msg)
  override def warn(msg: String): Unit = add("warn", msg)
  override def trace(ex: Throwable): Unit = {
    ex.getStackTrace.foreach(ste => trace(ste.toString))
    Option(ex.getCause).foreach { cause =>
      trace("Caused by:")
      trace(cause)
    }
  }

  def serverInfo(msg: String): Unit = add("server-info", msg)
  def serverError(msg: String): Unit = add("server-error", msg)
  private def trace(msg: String): Unit = add("trace", msg)

  override def isVerbose: Boolean = true
  override def asVerbose: Logger = this
  override def asDiscrete: Logger = this

  def dump(): Unit = println {
    s"""Logger contains the following messages:
       |${getMessages.map(s => s"[${s._1}] ${s._2}").mkString("\n  ", "\n  ", "\n")}
     """.stripMargin
  }

  /** Returns all the infos detected about the state of compilation */
  def compilingInfos: List[String] =
    getMessages.iterator.filter(m => m._1 == "info" && m._2.contains("Compiling ")).map(_._2).toList
}
