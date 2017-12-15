package bloop.logging

import java.io.{ByteArrayOutputStream, OutputStream, PrintStream}

import scala.sys.process

/**
 *  Wrap a `Logger` to conform to `scala.sys.process.ProcessLogger`. All
 *  messages are forwarded to the `underlying` logger.
 *
 *  @param underlying The logger to forward the messages to.
 */
class ProcessLogger(underlying: Logger) extends process.ProcessLogger {
  override def buffer[T](op: => T): T = op
  override def err(s: => String): Unit = underlying.error(s)
  override def out(s: => String): Unit = underlying.info(s)
}

object ProcessLogger {

  private val encoding = sys.props("file.encoding")

  /**
   * Creates an `OutputStream` from a logging function
   *
   * @param logFn A function that logs the given string to a logger.
   * @return An `OutputStream` that can be used as `System.in` or `System.err`.
   */
  def toOutputStream(logFn: String => Unit): OutputStream = {
    val outputStream = new OutputStream {
      private val buffer = new ByteArrayOutputStream

      override def write(x: Int): Unit = synchronized {
        buffer.write(x)
      }

      override def flush(): Unit = synchronized {
        val bytes = buffer.toByteArray()
        buffer.reset()
        val content = new String(bytes, encoding)
        logFn(content)
      }
    }
    outputStream
  }

  /**
   * Creates a `PrintStream` from a logging function
   *
   * @param logFn A function that logs the given string to a logger.
   * @return A `PrintStream` that can be used as `System.in` or `System.err`.
   */
  def toPrintStream(logFn: String => Unit): PrintStream = {
    val outputStream = toOutputStream(logFn)
    new PrintStream(outputStream, /* autoflush = */ true, encoding)
  }
}
