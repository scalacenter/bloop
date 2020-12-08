package bloop.scalajs.jsenv

import java.io.InputStream
import java.nio.ByteBuffer

import bloop.exec.Forker
import bloop.logging.{DebugFilter, Logger}
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess}
import monix.execution.atomic.AtomicBoolean

import scala.concurrent.Promise

final class NodeJsHandler(logger: Logger, exit: Promise[Unit], write: ByteBuffer => Unit)
    extends NuAbstractProcessHandler {
  implicit val debugFilter: DebugFilter = DebugFilter.Link
  private var currentStream: Option[InputStream] = None

  private var process: Option[NuProcess] = None

  override def onStart(nuProcess: NuProcess): Unit = {
    logger.debug(s"Process started at PID ${nuProcess.getPID}")
    process = Some(nuProcess)
  }

  /** @return false if we have nothing else to write */
  override def onStdinReady(output: ByteBuffer): Boolean = {
    write(output)
    logger.debug(s"Closing stdin stream (all JavaScript inputs have been sent)")
    process.get.closeStdin(false)
    false
  }

  val outBuilder = StringBuilder.newBuilder
  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (closed) {
      val remaining = outBuilder.mkString
      if (!remaining.isEmpty)
        logger.info(remaining)
    } else {
      Forker.onEachLine(buffer, outBuilder)(logger.info(_))
    }
  }

  // Scala-js redirects the error stream to out as well, so we duplicate its behavior
  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (closed) {
      val remaining = outBuilder.mkString
      if (!remaining.isEmpty)
        logger.info(remaining)
    } else {
      Forker.onEachLine(buffer, outBuilder)(logger.info(_))
    }
  }

  private val hasExited = AtomicBoolean(false)
  def cancel(): Unit = onExit(0)
  override def onExit(statusCode: Int): Unit = {
    if (!hasExited.getAndSet(true)) {
      logger.debug(s"Process exited with status code $statusCode")
      currentStream.foreach(_.close())
      currentStream = None
      exit.success(())
    }
  }
}
