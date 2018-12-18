package bloop.launcher

import java.io.{InputStream, OutputStream, PrintStream}
import java.nio.charset.{Charset, StandardCharsets}

import scala.collection.mutable.ArrayBuffer

final class LspParser(logsOut: PrintStream, charset: Charset) {
  def forward(in: InputStream, out: OutputStream): Unit = {
    var read: Int = 0
    var bytes: Array[Byte] = null
    var keepReading: Boolean = true
    do {
      bytes = new Array[Byte](1)
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

  def forward2(in: InputStream, out: OutputStream): Unit = {
    var read: Int = 0
    var bytes: Array[Byte] = null
    var keepReading: Boolean = true
    do {
      val available = in.available()
      bytes = if (available > 0) new Array[Byte](available) else new Array[Byte](1)
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

  private[this] def readHeaders(out: OutputStream): Unit = {
    if (data.size < 4) ()
    else {
      var i = 0
      while (i + 4 < data.size && !atDelimiter(i)) {
        i += 1
      }
      if (!atDelimiter(i)) ()
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
              readContent(out)
            } catch {
              case _: NumberFormatException =>
                printError(s"Expected Content-Length to be a number, obtained $n", logsOut)
                ()
            }
          case _ =>
            printError(s"Missing Content-Length key in headers $pairs", logsOut)
            ()
        }
      }
    }
  }

  private[this] def readContent(out: OutputStream): Unit = {
    if (contentLength > data.size) ()
    else {
      val contentBytes = new Array[Byte](contentLength)
      data.copyToArray(contentBytes)
      data.remove(0, contentLength)
      contentLength = -1

      header.foreach {
        case p @ (k, v) if p != EmptyPair =>
          val b = new StringBuilder()
          b.++=(k)
          b.++=(": ")
          b.++=(v)
          b.++=("\r\n")
          val headerString = b.toString
          out.write(headerString.getBytes(StandardCharsets.US_ASCII))
          out.flush()
        case _ => ()
      }

      out.write("\r\n".getBytes(charset))
      out.flush()
      out.write(contentBytes)
      out.flush()

      readHeaders(out)
    }
  }

  def parse(bytes: Array[Byte], out: OutputStream): Unit = {
    data ++= bytes
    if (contentLength < 0) readHeaders(out)
    else readContent(out)
  }
}
