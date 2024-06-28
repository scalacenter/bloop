package bloop.logging

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

import scala.util.control.NonFatal

final class DuplicatingOutputStream(
    val stdout: OutputStream,
    val logged: ByteArrayOutputStream,
    logger: Logger
) extends OutputStream {

  private val cache = new StringBuilder
  if (stdout == null || logged == null) {
    throw new NullPointerException()
  }

  @throws[IOException]
  override def write(b: Int): Unit = {
    stdout.write(b)
    logged.write(b)
    logAndReset()
  }

  @throws[IOException]
  override def write(b: Array[Byte]): Unit = {
    stdout.write(b)
    logged.write(b)
    logAndReset()
  }

  @throws[IOException]
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    stdout.write(b, off, len)
    logged.write(b, off, len)
    logAndReset()
  }

  private def logAndReset() = synchronized {
    try {
      cache.append(logged.toString())
      logged.reset()
      val logMessage = cache.toString.reverse.dropWhile(_ != '\n').reverse.trim()

      if (logMessage != "") {
        cache.delete(0, logMessage.size + 1)
        logger.info(logMessage)
      }
    } catch {
      case NonFatal(_) =>
    }
  }

  @throws[IOException]
  override def flush(): Unit = {
    stdout.flush()
    logged.flush()
  }

  @throws[IOException]
  override def close(): Unit = {
    try {
      stdout.close()
    } finally {
      logged.close()
    }
  }
}
