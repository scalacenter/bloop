package bloop.launcher
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}

import scala.util.Try

object Utils {
  case class StatusCommand(code: Int, output: String) {
    def isOk: Boolean = code == 0
  }

  private val isWindows: Boolean = scala.util.Properties.isWin
  private val cwd = Paths.get(System.getProperty("user.dir"))
  def runCommand(
      cmd: List[String],
      timeoutInSeconds: Option[Long],
      forwardOutputTo: Option[PrintStream] = None,
      beforeWait: NuProcess => Unit = _ => ()
  ): StatusCommand = {
    val outBuilder = StringBuilder.newBuilder
    def printOut(msg: String): Unit = {
      outBuilder.++=(msg)
      forwardOutputTo.foreach { ps => ps.print(msg)
      }
    }

    final class ProcessHandler extends NuAbstractProcessHandler {
      override def onStart(nuProcess: NuProcess): Unit = ()
      override def onExit(statusCode: Int): Unit = ()

      override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        val msg = new String(bytes, StandardCharsets.UTF_8)
        printOut(msg)
      }

      override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        val msg = new String(bytes, StandardCharsets.UTF_8)
        printOut(msg)
      }
    }

    val builder = new NuProcessBuilder(cmd: _*)
    builder.setProcessListener(new ProcessHandler)
    builder.setCwd(cwd)
    val process = builder.start()
    val code = Try(process.waitFor(timeoutInSeconds.getOrElse(0), TimeUnit.SECONDS)).getOrElse(1)
    StatusCommand(code, outBuilder.toString)
  }
}
