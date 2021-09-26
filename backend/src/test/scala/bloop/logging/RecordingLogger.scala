package bloop.logging

import java.io.PrintStream
import java.util.concurrent.ConcurrentLinkedQueue
import bloop.io.Environment.lineSeparator

import scala.collection.JavaConverters.asScalaIteratorConverter

class RecordingLogger(
    debug: Boolean = false,
    debugOut: Option[PrintStream] = None,
    val ansiCodesSupported: Boolean = true,
    val debugFilter: DebugFilter = DebugFilter.All
) extends Logger {
  private[this] val messages = new ConcurrentLinkedQueue[(String, String)]

  def clear(): Unit = messages.clear()

  def debugs: List[String] = getMessagesAt(Some("debug"))
  def infos: List[String] = getMessagesAt(Some("info"))
  def warnings: List[String] = getMessagesAt(Some("warn"))
  def errors: List[String] = getMessagesAt(Some("error"))

  def captureTimeInsensitiveInfos: List[String] = {
    infos.map(info => RecordingLogger.replaceTimingInfo(info))
  }

  def renderTimeInsensitiveInfos: String = {
    captureTimeInsensitiveInfos.mkString(lineSeparator)
  }

  def renderTimeInsensitiveTestInfos: String = {
    captureTimeInsensitiveInfos
      .filterNot(msg =>
        msg.startsWith("Compiling ") || msg.startsWith("Compiled ") || msg
          .startsWith("Generated ")
      )
      .mkString(lineSeparator)
  }

  def renderErrors(exceptContaining: String = ""): String = {
    val exclude = exceptContaining.nonEmpty
    val newErrors = if (!exclude) errors else errors.filterNot(_.contains(exceptContaining))
    newErrors.mkString(lineSeparator)
  }

  def getMessagesAt(level: Option[String]): List[String] = getMessages(level).map(_._2)
  def getMessages(): List[(String, String)] = getMessages(None)
  private def getMessages(level: Option[String]): List[(String, String)] = {
    val initialMsgs = messages.iterator.asScala
    val msgs = level match {
      case Some(level) => initialMsgs.filter(_._1 == level)
      case None => initialMsgs
    }

    msgs.map {
      // Remove trailing '\r' so that we don't have to special case for Windows
      case (category, msg0) =>
        val msg = if (msg0 == null) "<null reference>" else msg0
        (category, msg.stripSuffix("\r"))
    }.toList
  }

  override val name: String = "TestRecordingLogger"

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
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit =
    if (debugFilter.isEnabledFor(ctx)) add("debug", msg)

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
  override def withOriginId(originId: Option[String]): Logger = this

  def dump(out: PrintStream = System.out): Unit = {
    out.println {
      s"""Logger contains the following messages:
         |${getMessages.map(s => s"[${s._1}] ${s._2}").mkString("\n  ", "\n  ", "\n")}
     """.stripMargin
    }
  }

  /** Returns all infos that contain the word `Compiling` */
  def compilingInfos: List[String] = {
    getMessagesAt(Some("info")).filter(_.contains("Compiling "))
  }

  /** Returns all infos that contain the word `Test` */
  def startedTestInfos: List[String] = {
    getMessagesAt(Some("info")).filter(m => m.contains("Test ") && m.contains("started"))
  }

  def render: String = {
    getMessages()
      .map {
        case (level, msg) => s"[${level}] ${msg}"
      }
      .mkString(lineSeparator)
  }

  import java.nio.file.Path
  import java.nio.file.Files
  import java.nio.charset.StandardCharsets
  def writeToFile(id: String): Unit = {
    val path = Files.createTempFile("recording", id)
    Files.write(path, render.getBytes(StandardCharsets.UTF_8))
    System.err.println(s"Wrote logger ${id} output to ${path}")
  }
}

object RecordingLogger {
  def replaceTimingInfo(msg: String): String = {
    def representsTime(word: String, idx: Int): Boolean = {
      idx > 0 && {
        val lastChar = word.charAt(idx - 1)
        Character.isDigit(lastChar)
      }
    }

    msg
      .split("\\s+")
      .foldLeft(Nil: List[String]) {
        case (seen, word) =>
          val indexOfMs = word.lastIndexOf("ms")
          val indexOfS = word.lastIndexOf("s")
          if (representsTime(word, indexOfMs)) "???" :: seen
          else if (representsTime(word, indexOfS)) "???" :: seen
          else {
            seen match {
              case p :: ps =>
                if (word == "s" && Character.isDigit(p.last)) "???" :: ps
                else if (word == "ms" && Character.isDigit(p.last)) "???" :: ps
                else if (word.startsWith("seconds") && Character.isDigit(p.last))
                  "???" :: ps
                else if (word.startsWith("milliseconds") && Character.isDigit(p.last))
                  "???" :: ps
                else word :: seen
              case _ => word :: seen
            }
          }
      }
      .reverse
      .mkString(" ")
  }

}
