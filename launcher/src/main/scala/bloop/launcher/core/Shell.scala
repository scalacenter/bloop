package bloop.launcher.core

import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import java.util.logging.{Level, Logger}

import bloop.launcher.bsp.BspConnection
import bloop.launcher.util.Environment
import com.zaxxer.nuprocess.internal.BasePosixProcess
import com.zaxxer.nuprocess.{NuAbstractProcessHandler, NuProcess, NuProcessBuilder}

import scala.collection.mutable.ListBuffer
import scala.util.Try

/**
 * Defines shell utilities to run programs via system process.
 *
 * `runWithInterpreter` is necessary for testing because it allows us to shell out to an
 * independent shell whose environment variables we can modify (for example, we can modify
 * PATH so that the shell we run executes a mock version of bloop or python that fails).
 *
 * Note that there is an exception when the interpretation is enabled: `java` invocations
 * will be executed as they are because in Windows systems there can be execution problems
 * if the command is too long, which can happen with biggish classpaths
 * (see https://github.com/sbt/sbt-native-packager/issues/72).
 *
 * @param runWithInterpreter Decides whether we add a layer of indirection when running commands.
 *                           Uses `sh -c` in unix systems, `cmd.exe` in Windows systems */
final class Shell(runWithInterpreter: Boolean, detectPython: Boolean) {

  case class StatusCommand(code: Int, output: String) {
    def isOk: Boolean = code == 0
  }

  def runCommand(
      cmd0: List[String],
      cwd: Path,
      timeoutInSeconds: Option[Long],
      userOutput: Option[PrintStream]
  ): StatusCommand = runCommand(cmd0, cwd, timeoutInSeconds, None, userOutput)

  def runCommand(
      cmd0: List[String],
      cwd: Path,
      timeoutInSeconds: Option[Long],
      msgsBuffer: Option[ListBuffer[String]] = None,
      userOutput: Option[PrintStream] = None
  ): StatusCommand = {
    val isServerRun = cmd0.exists(_.contains("server"))
    val outBuilder = StringBuilder.newBuilder
    final class ProcessHandler extends NuAbstractProcessHandler {
      override def onStart(nuProcess: NuProcess): Unit = ()

      override def onExit(statusCode: Int): Unit = ()
      override def onStdout(buffer: ByteBuffer, closed: Boolean): Unit = {
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        val msg = new String(bytes, StandardCharsets.UTF_8)
        outBuilder.++=(msg)
        userOutput.foreach(out => out.print(msg))
        msgsBuffer.foreach(b => b += msg)
      }

      override def onStderr(buffer: ByteBuffer, closed: Boolean): Unit = {
        val bytes = new Array[Byte](buffer.remaining())
        buffer.get(bytes)
        val msg = new String(bytes, StandardCharsets.UTF_8)
        outBuilder.++=(msg)
        userOutput.foreach(out => out.print(msg))
        msgsBuffer.foreach(b => b += msg)
      }
    }

    val cmd = {
      if (Environment.isWindows && !Environment.isCygwin) {
        // Interpret all commands in Windows except java (used in tests) which causes
        // long classpath issues, see https://github.com/sbt/sbt-native-packager/issues/72
        if (cmd0.headOption.exists(_.startsWith("java"))) cmd0
        else List("cmd.exe", "/C") ++ cmd0
      } else {
        if (!runWithInterpreter) cmd0
        else List("sh", "-c", cmd0.mkString(" "))
      }
    }

    val builder = new NuProcessBuilder(cmd: _*)
    builder.setProcessListener(new ProcessHandler)
    builder.setCwd(cwd)

    val currentEnv = builder.environment()
    currentEnv.putAll(System.getenv())
    addAdditionalEnvironmentVariables(currentEnv)

    val process = builder.start()
    process.closeStdin(true)
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
      serverCmd: List[String],
      useTcp: Boolean,
      tempDir: Path
  ): (List[String], BspConnection) = {
    // For Windows, pick TCP until we fix https://github.com/scalacenter/bloop/issues/281
    if (useTcp || Environment.isWindows) {
      // We draw a random port from a "safe" tcp port range...
      val randomPort = Shell.portNumberWithin(17812, 18222)
      val cmd = serverCmd ++ List("bsp", "--protocol", "tcp", "--port", randomPort.toString)
      (cmd, BspConnection.Tcp("127.0.0.1", randomPort))
    } else {
      // Let's be conservative with names here, socket files have a 100 char limit
      val socketPath = tempDir.resolve(s"bsp.socket").toAbsolutePath
      Files.deleteIfExists(socketPath)
      val cmd = serverCmd ++ List("bsp", "--protocol", "local", "--socket", socketPath.toString)
      (cmd, BspConnection.UnixLocal(socketPath))
    }
  }

  def runBloopAbout(binaryCmd: List[String], out: PrintStream): Option[ServerStatus] = {
    // bloop is installed, let's check if it's running now
    val statusAbout = runCommand(binaryCmd ++ List("about"), Environment.cwd, Some(10))
    Some {
      if (statusAbout.isOk) ListeningAndAvailableAt(binaryCmd)
      else AvailableAt(binaryCmd)
    }
  }

  def connectToBloopPort(
      binaryCmd: List[String],
      port: Int,
      out: PrintStream
  ): Option[ServerStatus] = {
    import java.net.Socket
    var socket: Socket = null
    import scala.util.control.NonFatal
    try {
      socket = new Socket()
      socket.setReuseAddress(true)
      socket.setTcpNoDelay(true)
      import java.net.InetAddress
      import java.net.InetSocketAddress
      socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress, port))
      Some {
        if (socket.isConnected) ListeningAndAvailableAt(binaryCmd)
        else AvailableAt(binaryCmd)
      }
    } catch {
      case NonFatal(t) =>
        out.println(s"Connection to port $port failed with '${t.getMessage()}'")
        Some(AvailableAt(binaryCmd))
    } finally {
      if (socket != null) {
        try {
          val in = socket.getInputStream()
          val out = socket.getOutputStream()
          in.close()
          out.close()
          socket.close()
        } catch { case NonFatal(_) => }
      }
    }
  }

  def detectBloopInSystemPath(
      binaryCmd: List[String],
      out: PrintStream
  ): Option[ServerStatus] = {
    // --nailgun-help is always interpreted in the script, no connection with the server is required
    val status = runCommand(binaryCmd ++ List("--nailgun-help"), Environment.cwd, Some(2))
    if (!status.isOk) None
    else runBloopAbout(binaryCmd, out)
  }

  def isPythonInClasspath: Boolean = {
    if (!detectPython) false
    else runCommand(List("python", "--help"), Environment.cwd, Some(2)).isOk
  }
}

object Shell {
  Logger.getLogger(classOf[BasePosixProcess].getCanonicalName).setLevel(Level.SEVERE)

  def default: Shell = new Shell(false, true)

  def portNumberWithin(from: Int, to: Int): Int = {
    require(from > 24 && to < 65535)
    val r = new scala.util.Random
    from + r.nextInt(to - from)
  }
}
