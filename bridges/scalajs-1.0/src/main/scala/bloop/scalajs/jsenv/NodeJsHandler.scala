package bloop.scalajs.jsenv

import java.io.InputStream
import java.nio.ByteBuffer

import bloop.exec.Forker
import bloop.logging.{DebugFilter, Logger}
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess}
import monix.execution.atomic.AtomicBoolean
import org.scalajs.io.{FileVirtualBinaryFile, JSUtils, VirtualBinaryFile}

import scala.concurrent.Promise

final class NodeJsHandler(logger: Logger, exit: Promise[Unit], files: List[VirtualBinaryFile])
    extends NuAbstractProcessHandler {
  implicit val debugFilter: DebugFilter = DebugFilter.Link
  private val buffer = new Array[Byte](NuProcess.BUFFER_CAPACITY)
  private var currentFileIndex: Int = 0
  private var currentStream: Option[InputStream] = None

  private var process: Option[NuProcess] = None

  override def onStart(nuProcess: NuProcess): Unit = {
    logger.debug(s"Process started at PID ${nuProcess.getPID}")
    process = Some(nuProcess)
  }

  /** @return false if we have nothing else to write */
  override def onStdinReady(output: ByteBuffer): Boolean = {
    if (currentFileIndex < files.length) {
      files(currentFileIndex) match {
        case f: FileVirtualBinaryFile =>
          logger.debug(s"Sending js file $f...")
          val path = f.file.getAbsolutePath
          val str = s"""require("${JSUtils.escapeJS(path)}");"""
          output.put(str.getBytes("UTF-8"))
          currentFileIndex += 1

        case f =>
          val in = currentStream.getOrElse {
            logger.debug(s"Sending js file $f...")
            f.inputStream
          }
          currentStream = Some(in)

          if (in.available() != 0) {
            val read = in.read(buffer)
            output.put(buffer, 0, read)
          } else {
            output.put('\n'.toByte)

            in.close()
            currentStream = None
            currentFileIndex += 1
          }
      }

      output.flip()
    }

    if (currentFileIndex == files.length) {
      logger.debug(s"Closing stdin stream (all js files have been sent)")
      process.get.closeStdin(false)
    }

    currentFileIndex < files.length
  }

  val outBuilder = StringBuilder.newBuilder
  override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (closed) {
      val remaining = outBuilder.mkString
      if (!remaining.isEmpty)
        logger.info(remaining)
    } else {
      Forker.linesFrom(buffer, outBuilder).foreach(logger.info(_))
    }
  }

  // Scala-js redirects the error stream to out as well, so we duplicate its behavior
  override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
    if (closed) {
      val remaining = outBuilder.mkString
      if (!remaining.isEmpty)
        logger.info(remaining)
    } else {
      Forker.linesFrom(buffer, outBuilder).foreach(logger.info(_))
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
