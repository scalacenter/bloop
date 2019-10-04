package bloop.bloopgun.core

import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import bloop.bloopgun.util.Environment
import bloop.bloopgun.core.Shell.StatusCommand

import scala.collection.mutable.ListBuffer
import scala.util.Try

import org.zeroturnaround.exec.ProcessExecutor
import org.zeroturnaround.exec.listener.ProcessListener
import org.zeroturnaround.exec.stream.ExecuteStreamHandler
import org.zeroturnaround.exec.stream.LogOutputStream
import snailgun.logging.Logger
import bloop.bloopgun.ServerConfig

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
  def runCommand(
      cmd0: List[String],
      cwd: Path,
      timeoutInSeconds: Option[Long],
      userOutput: Option[PrintStream],
      attachTerminal: Boolean,
      useJdkProcessAndInheritIO: Boolean
  ): StatusCommand =
    runCommand(
      cmd0,
      cwd,
      timeoutInSeconds,
      None,
      userOutput,
      attachTerminal,
      useJdkProcessAndInheritIO
    )

  def runCommandInheritingIO(
      cmd0: List[String],
      cwd: Path,
      timeoutInSeconds: Option[Long],
      attachTerminal: Boolean
  ): StatusCommand = {
    import scala.collection.JavaConverters._
    val builder = new ProcessBuilder()
    val newEnv = builder.environment()
    newEnv.putAll(System.getenv())
    addAdditionalEnvironmentVariables(newEnv)

    val cmd = deriveCommandForPlatform(cmd0, attachTerminal)
    val code = builder.command(cmd.asJava).directory(cwd.toFile).inheritIO().start().waitFor()
    // Returns empty output because IO is inherited, meaning all IO is passed to the default stdout
    StatusCommand(code, "")
  }

  def runCommand(
      cmd0: List[String],
      cwd: Path,
      timeoutInSeconds: Option[Long],
      msgsBuffer: Option[ListBuffer[String]] = None,
      userOutput: Option[PrintStream] = None,
      attachTerminal: Boolean = false,
      useJdkProcessAndInheritIO: Boolean = false
  ): StatusCommand = {
    assert(cmd0.nonEmpty)
    val outBuilder = StringBuilder.newBuilder
    val cmd = deriveCommandForPlatform(cmd0, attachTerminal)
    val executor = new ProcessExecutor(cmd: _*)

    val newEnv = executor.getEnvironment()
    newEnv.putAll(System.getenv())
    addAdditionalEnvironmentVariables(newEnv)

    executor
      .directory(cwd.toFile)
      .destroyOnExit()
      .redirectErrorStream(true)
      .redirectOutput(new LogOutputStream() {
        override def processLine(line: String): Unit = {
          outBuilder.++=(line).++=(System.lineSeparator())
          userOutput.foreach(out => out.println(line))
          msgsBuffer.foreach(b => b += line)
        }
      })

    timeoutInSeconds.foreach { seconds =>
      executor.timeout(seconds, TimeUnit.SECONDS)
    }

    val code = Try(executor.execute().getExitValue()).getOrElse(1)
    StatusCommand(code, outBuilder.toString)
  }

  def deriveCommandForPlatform(
      cmd0: List[String],
      attachTerminal: Boolean
  ): List[String] = {
    val isJavaCmd = cmd0.headOption.exists(_ == "java")
    if (Environment.isWindows && !Environment.isCygwin) {
      if (isJavaCmd) cmd0 else List("cmd.exe", "/C") ++ cmd0
    } else {
      if (!runWithInterpreter && !attachTerminal) {
        if (isJavaCmd) cmd0 else "sh" :: cmd0
      } else {
        val cmd = if (attachTerminal) s"(${cmd0.mkString(" ")}) </dev/tty" else cmd0.mkString(" ")
        List("sh", "-c", cmd)
      }
    }
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

  def findCmdInPath(cmd0: String): StatusCommand = {
    val cmd = {
      if (Environment.isWindows && !Environment.isCygwin) List("where", cmd0)
      // https://unix.stackexchange.com/questions/85249/why-not-use-which-what-to-use-then
      else List("command", "-v", cmd0)
    }

    runCommand(cmd, Environment.cwd, None)
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

  def runBloopAbout(binaryCmd: List[String], out: PrintStream): Option[ServerStatus] = {
    val statusAbout =
      runCommand(binaryCmd ++ List("about"), Environment.cwd, Some(10))
    Some {
      if (statusAbout.isOk) ListeningAndAvailableAt(binaryCmd)
      else AvailableWithCommand(binaryCmd)
    }
  }

  def connectToBloopPort(
      config: ServerConfig,
      logger: Logger
  ): Boolean = {
    import java.net.Socket
    var socket: Socket = null
    import scala.util.control.NonFatal
    try {
      socket = new Socket()
      socket.setReuseAddress(true)
      socket.setTcpNoDelay(true)
      import java.net.InetAddress
      import java.net.InetSocketAddress
      socket.connect(new InetSocketAddress(config.userOrDefaultHost, config.userOrDefaultPort))
      socket.isConnected()
    } catch {
      case NonFatal(t) =>
        logger.info("Attempting a connection to the server...")
        logger.debug(s"Connection to port $config failed with '${t.getMessage()}'")
        false
    } finally {
      if (socket != null) {
        try {
          socket.shutdownInput()
          socket.shutdownOutput()
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
    val status =
      runCommand(binaryCmd ++ List("--nailgun-help"), Environment.cwd, Some(2))
    if (!status.isOk) None
    else runBloopAbout(binaryCmd, out)
  }

  def isPythonInClasspath: Boolean = {
    if (!detectPython) false
    else runCommand(List("python", "--help"), Environment.cwd, Some(2)).isOk
  }
}

object Shell {
  def default: Shell = new Shell(false, true)

  def portNumberWithin(from: Int, to: Int): Int = {
    require(from > 24 && to < 65535)
    val r = new scala.util.Random
    from + r.nextInt(to - from)
  }

  case class StatusCommand(code: Int, output: String) {
    def isOk: Boolean = code == 0

    // assuming if it's ok, we don't need exit code
    def toEither: Either[(Int, String), String] =
      if (isOk) {
        Right(output)
      } else {
        Left(code -> output)
      }
  }
}
