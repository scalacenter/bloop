package bloop.launcher
import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.Properties
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
      forwardOutputTo.foreach { ps =>
        ps.print(msg)
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

    val currentEnv = builder.environment()
    currentEnv.putAll(System.getenv())
    addAdditionalSystemProperties(currentEnv)

    val process = builder.start()
    val code = Try(process.waitFor(timeoutInSeconds.getOrElse(0), TimeUnit.SECONDS)).getOrElse(1)
    StatusCommand(code, outBuilder.toString)
  }

  // Add coursier cache and ivy home system properties if set and not available in env
  private def addAdditionalSystemProperties(env: java.util.Map[String, String]): Unit = {
    Option(System.getProperty("coursier.cache")).foreach { cache =>
      val coursierKey = "COURSIER_CACHE"
      if (env.containsKey(coursierKey)) ()
      else env.put(coursierKey, cache)
    }

    Option(System.getProperty("ivy.home")).foreach { ivyHome =>
      val ivyHomeKey = "IVY_HOME"
      if (env.containsKey(ivyHomeKey)) ()
      else env.put(ivyHomeKey, ivyHome)
    }
  }

  // A valid tcp random port can be fr
  def portNumberWithin(from: Int, to: Int): Int = {
    require(from > 24 && to < 65535)
    val r = new scala.util.Random
    from + r.nextInt(to - from)
  }

  def startThread(name: String, daemon: Boolean)(thunk: => Unit): Thread = {
    val thread = new Thread {
      override def run(): Unit = thunk
    }

    thread.setName(name)
    // The daemon will be set to false when the embedded mode is run
    thread.setDaemon(daemon)
    thread.start()
    thread
  }

  def deriveBspInvocation(
      bloopServerCmd: List[String],
      useTcp: Boolean,
      tempDir: Path
  ): List[String] = {
    // For Windows, pick TCP until we fix https://github.com/scalacenter/bloop/issues/281
    if (useTcp || isWindows) {
      // We draw a random port from a "safe" tcp port range...
      val randomPort = Utils.portNumberWithin(17812, 18222).toString
      bloopServerCmd ++ List("bsp", "--protocol", "tcp", "--port", randomPort)
    } else {
      // Let's be conservative with names here, socket files have a 100 char limit
      val socketPath = tempDir.resolve(s"bsp.socket").toAbsolutePath.toString
      bloopServerCmd ++ List("bsp", "--protocol", "local", "--socket", socketPath)
    }
  }

  def runBloopAbout(binary: String, additionalArgs: List[String]): Option[ServerState] = {
    // bloop is installed, let's check if it's running now
    val statusAbout = Utils.runCommand(List(binary, "about") ++ additionalArgs, Some(10))
    Some {
      if (statusAbout.isOk) ListeningAndAvailableAt(binary)
      else AvailableAt(binary)
    }
  }

  def detectBloopInSystemPath(binary: String, additionalArgs: List[String]): Option[ServerState] = {
    // --nailgun-help is always interpreted in the script, no connection with the server is required
    val status = Utils.runCommand(List(binary, "--nailgun-help"), Some(10))
    if (!status.isOk) None
    else runBloopAbout(binary, additionalArgs)
  }
}
