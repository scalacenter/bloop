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

  /**
   * Creates a `PrintStream` from a logging function
   *
   * @param logger A function that logs the given string to a logger.
   * @return A `PrintStream` that can be used as `System.in` or `System.err`.
   */
  def toStream(logger: String => Unit): PrintStream = {
    val encoding = sys.props("file.encoding")
    val outputStream = new OutputStream {
      private val buffer = new ByteArrayOutputStream

      override def write(x: Int): Unit = synchronized {
        buffer.write(x)
      }

      override def flush(): Unit = synchronized {
        val bytes = buffer.toByteArray()
        buffer.reset()
        val content = new String(bytes, encoding)
        logger(content)
      }
    }
    new PrintStream(outputStream, /* autoflush = */ true, encoding)
  }
}
