package bloop.launcher

import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, Paths}
import java.util.concurrent.TimeUnit

import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}

import scala.util.Try

class Shell private (runWithInterpreter: Boolean, whitelist: List[String]) {
  case class StatusCommand(code: Int, output: String) {
    def isOk: Boolean = code == 0
  }

  protected val isWindows: Boolean = scala.util.Properties.isWin
  protected val cwd = Paths.get(System.getProperty("user.dir"))
  def runCommand(
      cmd0: List[String],
      timeoutInSeconds: Option[Long]
  ): StatusCommand = {
    val outBuilder = StringBuilder.newBuilder
    final class ProcessHandler extends NuAbstractProcessHandler {
      override def onStart(nuProcess: NuProcess): Unit = ()
      override def onExit(statusCode: Int): Unit = ()

      override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        val msg = new String(bytes, StandardCharsets.UTF_8)
        outBuilder.++=(msg)
      }

      override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        val msg = new String(bytes, StandardCharsets.UTF_8)
        outBuilder.++=(msg)
      }
    }

    val cmd = {
      if (!runWithInterpreter) cmd0
      else {
        if (cmd0.headOption.exists(c => whitelist.contains(c))) cmd0
        else {
          if (isWindows) List("cmd.exe", "/C") ++ cmd0
          // If sh -c is used, wrap the whole command in single quotes
          else List("sh", "-c", cmd0.mkString(" "))
        }
      }
    }

    val builder = new NuProcessBuilder(cmd: _*)
    builder.setProcessListener(new ProcessHandler)
    builder.setCwd(cwd)

    val currentEnv = builder.environment()
    currentEnv.putAll(System.getenv())
    addAdditionalEnvironmentVariables(currentEnv)

    val process = builder.start()
    val code = Try(process.waitFor(timeoutInSeconds.getOrElse(0), TimeUnit.SECONDS)).getOrElse(1)
    StatusCommand(code, outBuilder.toString)
  }

  // Add coursier cache and ivy home system properties if set and not available in env
  protected def addAdditionalEnvironmentVariables(env: java.util.Map[String, String]): Unit = {
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
      val randomPort = portNumberWithin(17812, 18222).toString
      bloopServerCmd ++ List("bsp", "--protocol", "tcp", "--port", randomPort)
    } else {
      // Let's be conservative with names here, socket files have a 100 char limit
      val socketPath = tempDir.resolve(s"bsp.socket").toAbsolutePath.toString
      bloopServerCmd ++ List("bsp", "--protocol", "local", "--socket", socketPath)
    }
  }

  def runBloopAbout(binaryCmd: List[String], out: PrintStream): Option[ServerState] = {
    // bloop is installed, let's check if it's running now
    val statusAbout = runCommand(binaryCmd ++ List("about"), Some(10))
    //System.out.println(statusAbout.toString)
    Some {
      if (statusAbout.isOk) ListeningAndAvailableAt(binaryCmd)
      else AvailableAt(binaryCmd)
    }
  }

  def detectBloopInSystemPath(
      binaryCmd: List[String],
      out: PrintStream
  ): Option[ServerState] = {
    // --nailgun-help is always interpreted in the script, no connection with the server is required
    val status = runCommand(binaryCmd ++ List("--nailgun-help"), Some(2))
    if (!status.isOk) None
    else runBloopAbout(binaryCmd, out)
  }

  def isPythonInClasspath: Boolean = {
    runCommand(List("python", "--help"), Some(2)).isOk
  }
}

object Shell {
  def apply(): Shell = new Shell(false, Nil)

  /**
   * Defines an instance of a shell that adds a layer of indirection when executing scripts.
   *
   * This extra layer of indirection allows us to modify the PATH environment variable in our
   * tests and use that PATH to find binaries (such as bloop). We also whitelist python so
   * that it always uses the host python computer.
   *
   * @return A shell instance prepared to be used for tests.
   */
  private[launcher] def forTests: Shell = new Shell(true, List()) {
    // Note this will fail in operating systems that are not Windows but don't provide `sh`
    val shellInterpreter = if (isWindows) List("cmd.exe", "/C") else List("sh", "-c")
  }
}
