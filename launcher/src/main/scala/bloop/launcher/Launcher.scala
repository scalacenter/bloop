package bloop.launcher

import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}

import com.zaxxer.nuprocess.NuProcess
import io.github.soc.directories.ProjectDirectories

object Launcher {
  private val isWindows: Boolean = scala.util.Properties.isWin
  private val homeDirectory: Path = Paths.get(System.getProperty("user.home"))
  private val projectDirectories: ProjectDirectories =
    ProjectDirectories.from("", "", "bloop")
  private val bloopDataLogsDir: Path =
    Files.createDirectories(Paths.get(projectDirectories.dataDir).resolve("logs"))

  // Override print to redirect msgs to System.err (as mandated by the BSP spec)
  def println(msg: String): Unit = System.err.println(msg)
  def printError(msg: String): Unit = println(s"error: ${msg}")
  def printQuoted(msg: String): Unit = {
    println {
      msg
        .split(System.lineSeparator())
        .map(l => s"> $l")
        .mkString(System.lineSeparator())
    }
  }

  def main(args: Array[String]): Unit = {
    println("Starting the bsp launcher for bloop")
    if (isPythonInClasspath) {
      printError("Python must be installed, it's not accessible via classpath")
    }
  }

  sealed trait ServerState
  case class AvailableAt(binary: String) extends ServerState
  case class ListeningAndAvailableAt(binary: String) extends ServerState

  def isPythonInClasspath: Boolean = {
    Utils.runCommand(List("python", "--help"), Some(3)).isOk
  }

  def detectBloopInClasspath(binary: String): Option[ServerState] = {
    // --nailgun-help is always interpreted in the script, no connection with the server is required
    val status = Utils.runCommand(List(binary, "--nailgun-help"), Some(10))
    if (!status.isOk) None
    else {
      // bloop is installed, let's check if it's running now
      val statusAbout = Utils.runCommand(List(binary, "about"), Some(10))
      Some {
        if (statusAbout.isOk) ListeningAndAvailableAt(binary)
        else AvailableAt(binary)
      }
    }
  }

  // we should detect python is installed?
  // we should detect the bloop version around here

  def recoverFromUninstalledServer: ServerState = {

  }

  def detectServerState: ServerState = {
    detectBloopInClasspath("bloop").getOrElse {
      // The binary is not available in the classpath
      val homeBloopDir = homeDirectory.resolve(".bloop")
      if (Files.exists(homeBloopDir)) {
        // This is the nailgun script that we can use to run bloop
        val binaryName = if (isWindows) "bloop.cmd" else "bloop"
        val pybloop = homeBloopDir.resolve(binaryName)
        if (Files.exists(pybloop)) {
          val binaryInHome = pybloop.normalize.toAbsolutePath.toString
          detectBloopInClasspath(binaryInHome).getOrElse(recoverFromUninstalledServer)
        } else {
          // The installation in `.bloop` seems off, consider uninstalled
          recoverFromUninstalledServer
        }
      } else recoverFromUninstalledServer
    }
  }

  // A valid tcp random port can be fr
  private def portNumberWithin(from: Int, to: Int): Int = {
    require(from > 24 && to < 65535)
    val r = new scala.util.Random
    from + r.nextInt(to - from)
  }

  private val alreadyInUseMsg = "Address already in use"
  def connectToServer(): Unit = {
    def establishBspConnection(binary: String, useTcp: Boolean, attempts: Int = 1): Unit = {
      val bspCmd: List[String] = {
        // For Windows, pick TCP until we fix https://github.com/scalacenter/bloop/issues/281
        if (useTcp || isWindows) {
          val randomPort = portNumberWithin(17812, 18222).toString
          List(binary, "bsp", "--protocol", "tcp", "--port", randomPort)
        } else {
          // Let's be conservative with names here, socket files have a 100 char limit
          val tempDir = Files.createTempDirectory(s"bsp-conn")
          val socketPath = tempDir.resolve(s"bsp.socket").toAbsolutePath.toString
          List(binary, "bsp", "--protocol", "local", "--socket", socketPath)
        }
      }

      // The nailgun bloop script is in the classpath, let's run it
      val status = Utils.runCommand(
        bspCmd,
        Some(0),
        forwardOutputTo = Some(System.out),
        beforeWait = redirectStdinTo(_)
      )

      if (status.isOk) {} else {
        if (status.output.contains(alreadyInUseMsg)) {
          if (attempts <= 5) establishBspConnection(binary, useTcp, attempts + 1)
          else {
            // Too many attempts, the connection seems off, let's report
            printError(s"Received '${alreadyInUseMsg}' 5 times, aborting connection...")
            printError(s"Printing the output of the last invocation: ${bspCmd.mkString(" ")}")
            printQuoted(status.output)
            exit()
          }
        } else {
          printError(s"Failed to establish bsp connection with ${bspCmd.mkString(" ")}")
          printQuoted(status.output)

          println("\nTrying connection via TCP before failing...")
          establishBspConnection(binary, true, attempts + 1)
          exit()
        }
      }
    }

    // We don't use tcp by default, only if a local connection fails
    detectServerState match {
      case ListeningAndAvailableAt(binary) =>
        establishBspConnection(binary, false)
      case AvailableAt(binary) =>
        startServer(binary)
        establishBspConnection(binary, false)
    }
  }

  def startServer(binary: String): Unit = {
    val serverThread = new Thread {
      override def run(): Unit = {
        // Running 'bloop server' should always work if v > 1.1.0
        Utils.runCommand(List(binary, "server"), None)
      }
    }

    serverThread.setName("bsp-server-driver")
    // Extremely important so that future invocations can reuse the same server
    serverThread.setDaemon(true)
    serverThread.start()
  }

  private var shutdownInput: Boolean = false
  def redirectStdinTo(process: NuProcess): Unit = {
    val backgroundThread = new Thread {
      private val in = System.in
      override def run(): Unit = {
        // Redirect input in chunks of 128 (to ensure that messages are not buffered)
        val buffer = new Array[Byte](128)
        val read = in.read(buffer, 0, buffer.length)
        if (read == -1 || shutdownInput || !process.isRunning) ()
        else process.writeStdin(ByteBuffer.wrap(buffer))
      }
    }

    backgroundThread.setName("bloop-bsp-launcher-stdin")
    backgroundThread.start()
  }

  def exit(): Unit = {
    shutdownInput = true
    sys.exit(1)
  }
}
