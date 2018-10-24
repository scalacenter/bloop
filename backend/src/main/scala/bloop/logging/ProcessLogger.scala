package bloop.logging

import java.io.{
  BufferedReader,
  ByteArrayOutputStream,
  InputStream,
  InputStreamReader,
  OutputStream,
  PrintStream
}
import scala.annotation.tailrec

/**
 * Logs the output on stdout and std err of `process` to the `underlying` logger
 *
 * @param underlying The logger that receives the output of the process
 * @param process    The process whose output must be logged.
 */
class ProcessLogger(underlying: Logger, process: Process) {
  private[this] val processOut = process.getInputStream
  private[this] val processErr = process.getErrorStream

  def start(): Unit = {
    implicit val ctx: DebugFilter = DebugFilter.All
    underlying.printDebug("Starting to log output from process...")
    new StreamLogger(underlying.info, processOut).start()
    new StreamLogger(underlying.error, processErr).start()
  }
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
      private[this] var dirty = false
      private[this] val buffer = new ByteArrayOutputStream

      override def write(x: Int): Unit = synchronized {
        dirty = true
        if (x == '\n') flush()
        else buffer.write(x)
      }

      override def flush(): Unit = synchronized {
        if (dirty) {
          dirty = false
          val bytes = buffer.toByteArray()
          buffer.reset()
          val content = new String(bytes, encoding)
          logFn(content)
        }
      }

      override def close(): Unit = {
        flush()
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
    new PrintStream(outputStream, /* autoflush = */ false, encoding)
  }
}

/**
 * Pass all data from `stream` to `logFn`
 *
 * @param logFn  The handler that receives data from the `stream`
 * @param stream The stream that produces the data.
 */
private class StreamLogger(logFn: String => Unit, stream: InputStream) extends Thread {
  private[this] val reader = new BufferedReader(new InputStreamReader(stream))

  @tailrec
  override final def run(): Unit = {
    Option(reader.readLine()) match {
      case Some(line) => logFn(line); run()
      case None => ()
    }
  }
}
