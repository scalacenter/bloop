package bloop.launcher

import java.io.{InputStream, OutputStream, PrintStream}
import java.nio.charset.{Charset, StandardCharsets}

import scala.collection.mutable.ArrayBuffer

class LspParser(logsOut: PrintStream, charset: Charset) {
  def forward(in: InputStream, out: OutputStream): Unit = {
    var read: Int = 0
    var bytes: Array[Byte] = null
    var keepReading: Boolean = true
    do {
      val available = in.available()
      bytes = new Array[Byte](1)
      read = in.read(bytes)// if (available > 0) in.read(bytes) else in.read()
      if (read == -1) {
        keepReading = false
      } else {
        if (read != 0) {
          val data = new Array[Byte](read)
          bytes.copyToArray(data, 0, read)
          parse(data, out)
        }
      }
    } while (keepReading)
  }

  def forward2(in: InputStream, out: OutputStream): Unit = {
    var read: Int = 0
    var bytes: Array[Byte] = null
    var keepReading: Boolean = true
    val buffer = new ArrayBuffer[Byte]()
    do {
      out.flush()
      val available = in.available()
      bytes = if (available > 0) new Array[Byte](available) else new Array[Byte](0)
      //bytes = new Array[Byte](1)
      read = in.read(bytes)
      if (read == -1) {
        keepReading = false
      } else {
        if (read != 0) {
          val data = new Array[Byte](read)
          bytes.copyToArray(data, 0, read)
          parse(data, out)
        }
      }
    } while (keepReading)
  }

  private[this] val EmptyPair = "" -> ""
  private[this] val data = ArrayBuffer.empty[Byte]
  private[this] var contentLength = -1
  private[this] var header = Map.empty[String, String]
  private[this] def atDelimiter(idx: Int): Boolean = {
    data.size >= idx + 4 &&
    data(idx) == '\r' &&
    data(idx + 1) == '\n' &&
    data(idx + 2) == '\r' &&
    data(idx + 3) == '\n'
  }

  sealed trait ReadAction
  case object Stop extends ReadAction
  case object Continue extends ReadAction

  private[this] def readHeaders(): ReadAction = {
    if (data.size < 4) Continue
    else {
      var i = 0
      while (i + 4 < data.size && !atDelimiter(i)) {
        i += 1
      }
      if (!atDelimiter(i)) Continue
      else {
        val bytes = new Array[Byte](i)
        data.copyToArray(bytes)
        data.remove(0, i + 4)
        val headers = new String(bytes, StandardCharsets.US_ASCII)
        val pairs: Map[String, String] = headers
          .split("\r\n")
          .iterator
          .filterNot(_.trim.isEmpty)
          .map { line =>
            line.split(":") match {
              case Array(key, value) => key.trim -> value.trim
              case _ =>
                printError(s"Malformed input: $line", logsOut)
                EmptyPair
            }
          }
          .toMap
        pairs.get("Content-Length") match {
          case Some(n) =>
            try {
              contentLength = n.toInt
              header = pairs
              readContent(logsOut)
            } catch {
              case _: NumberFormatException =>
                printError(s"Expected Content-Length to be a number, obtained $n", logsOut)
                Continue
            }
          case _ =>
            printError(s"Missing Content-Length key in headers $pairs", logsOut)
            Continue
        }
      }
    }
  }

  private[this] def readContent(out: OutputStream): ReadAction = {
    if (contentLength > data.size) Continue
    else {
      val contentBytes = new Array[Byte](contentLength)
      data.copyToArray(contentBytes)
      data.remove(0, contentLength)
      contentLength = -1

      println(header.mkString(" . "), logsOut)
      println(new String(contentBytes, StandardCharsets.UTF_8), logsOut)
      header.foreach {
        case p @ (k, v) if p != EmptyPair =>
          val b = new StringBuilder()
          b.++=(k)
          b.++=(": ")
          b.++=(v)
          if (!v.endsWith("\r\n")) b.++=("\r\n")
          val headerString = b.toString
          out.write(headerString.getBytes(StandardCharsets.US_ASCII))
          out.flush()
          println(s"final msg ${headerString.toCharArray.toList}", logsOut)
          println(s"final msg length ${headerString.toCharArray.length}", logsOut)
        case _ => ()
      }

      out.write("\r\n".getBytes(charset))
      println("End header section", logsOut)
      println("\r\n".toCharArray.length.toString, logsOut)
      println("\r\n".toCharArray.toList.mkString(", "), logsOut)
      println("Start contents", logsOut)
      out.write(contentBytes)
      println(s"writing message ${new String(contentBytes, charset).toCharArray.toList}", logsOut)
      println(s"rela length ${contentBytes.length}", logsOut)
      println(s"length${new String(contentBytes, charset).toCharArray.length}", logsOut)
      out.flush()

      readHeaders()
    }
  }

  def parse(bytes: Array[Byte], out: OutputStream): ReadAction = {
    //println(s"reading ${new String(bytes, charset).toArray.toList}", logsOut)
    data ++= bytes

    if (contentLength < 0) readHeaders()
    else readContent(out)
  }
}
