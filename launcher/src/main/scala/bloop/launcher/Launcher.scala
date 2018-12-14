package bloop.launcher

import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}

import com.zaxxer.nuprocess.NuProcess
import io.github.soc.directories.ProjectDirectories

import scala.collection.mutable
import scala.util.control.NonFatal

object Launcher {
  private lazy val tempDir = Files.createTempDirectory(s"bsp-launcher")
  private val isWindows: Boolean = scala.util.Properties.isWin
  private val homeDirectory: Path = Paths.get(System.getProperty("user.home"))
  private val projectDirectories: ProjectDirectories =
    ProjectDirectories.from("", "", "bloop")
  private val bloopDataLogsDir: Path =
    Files.createDirectories(Paths.get(projectDirectories.dataDir).resolve("logs"))

  // Override print to redirect msgs to System.err (as mandated by the BSP spec)
  def print(msg: String): Unit = System.err.print(msg)
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
    // TODO: Add support of java arguments passed in to the launcher
    if (args.size == 1) {
      val bloopVersion = args.apply(0)
      runLauncher(bloopVersion)
    } else {
      printError("The bloop launcher accepts only one argument: the bloop version.")
    }
  }

  def runLauncher(bloopVersionToInstall: String): Unit = {
    println("Starting the bsp launcher for bloop")
    if (isPythonInClasspath) {
      printError("Python must be installed, it's not accessible via classpath")
    }
  }

  sealed trait ServerState
  case class AvailableAt(binary: String) extends ServerState
  case class ResolvedAt(files: Seq[Path]) extends ServerState
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

  def connectToServer(bloopVersion: String): Unit = {
    def handleServerState(establishConnection: => Unit): Unit = {
      serverStatusCommand match {
        case Some((cmd, status)) if status.isOk =>
          printError(s"Unexpected successful early exit of the bsp server with '$cmd'!")
          if (!status.output.isEmpty) printQuoted(status.output)
          exitWithFailure()

        case Some((cmd, status)) =>
          printError(s"Failed to start bloop bsp server with '$cmd'!")
          if (!status.output.isEmpty) printQuoted(status.output)
          exitWithFailure()

        case None => establishConnection
      }
    }

    detectServerState(bloopVersion).getOrElse(recoverFromUninstalledServer(bloopVersion)) match {
      case ListeningAndAvailableAt(binary) =>
        // We don't use tcp by default, only if a local connection fails
        establishBspConnectionViaBinary(binary, false)

      case AvailableAt(binary) =>
        // Start the server if we only have a bloop binary
        startServerViaScript(binary)
        // Sleep a little bit to give some time to the server to start up
        println("Server was started in a thread, waiting until it's up and running...")
        // Let's be conservative in Windows as any IO is usually considerably slower
        if (isWindows) Thread.sleep(700) else Thread.sleep(250)

        handleServerState {
          // The server cmd is still running, try connection
          establishBspConnectionViaBinary(binary, false)
        }

      // The build server has been resolved because the python installation failed in this system
      case ResolvedAt(classpath) =>
        startServerViaClasspath(classpath)

        // Sleep a little bit to give some time to the server to start up
        println("Server was started in a thread, waiting until it's up and running...")
        // We need to be less conservative with the java execution in Windows than the script above
        if (isWindows) Thread.sleep(500) else Thread.sleep(250)

        handleServerState {
          // The server cmd is still running, try connection
          establishManualBspConnection(false)
        }
    }
  }

  def detectServerState(bloopVersion: String): Option[ServerState] = {
    detectBloopInClasspath("bloop").orElse {
      // The binary is not available in the classpath
      val homeBloopDir = homeDirectory.resolve(".bloop")
      if (!Files.exists(homeBloopDir)) None
      else {
        // This is the nailgun script that we can use to run bloop
        val binaryName = if (isWindows) "bloop.cmd" else "bloop"
        val pybloop = homeBloopDir.resolve(binaryName)
        if (!Files.exists(pybloop)) None
        else {
          val binaryInHome = pybloop.normalize.toAbsolutePath.toString
          detectBloopInClasspath(binaryInHome)
        }
      }
    }
  }

  // A valid tcp random port can be fr
  private def portNumberWithin(from: Int, to: Int): Int = {
    require(from > 24 && to < 65535)
    val r = new scala.util.Random
    from + r.nextInt(to - from)
  }

  private val alreadyInUseMsg = "Address already in use"

  // TODO: Improve to enable repeated local connections in case the server is not yet up and running
  def establishBspConnectionViaBinary(binary: String, useTcp: Boolean, attempts: Int = 1): Unit = {
    val bspCmd: List[String] = {
      // For Windows, pick TCP until we fix https://github.com/scalacenter/bloop/issues/281
      if (useTcp || isWindows) {
        val randomPort = portNumberWithin(17812, 18222).toString
        List(binary, "bsp", "--protocol", "tcp", "--port", randomPort)
      } else {
        // Let's be conservative with names here, socket files have a 100 char limit
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

    if (status.isOk) {
      // The bsp connection was finished successfully, the launcher exits too
      println("The bsp connection finished successfully, exiting...")
      exitWithSuccess()
    } else {
      if (status.output.contains(alreadyInUseMsg)) {
        if (attempts <= 5) establishBspConnectionViaBinary(binary, useTcp, attempts + 1)
        else {
          // Too many attempts, the connection seems off, let's report
          printError(s"Received '${alreadyInUseMsg}' > 5 times when attempting connection...")
          printError(s"Printing the output of the last invocation: ${bspCmd.mkString(" ")}")
          printQuoted(status.output)
          exitWithFailure()
        }
      } else {
        printError(s"Failed to establish bsp connection with ${bspCmd.mkString(" ")}")
        printQuoted(status.output)

        println("\nTrying connection via TCP before failing...")
        establishBspConnectionViaBinary(binary, true, attempts + 1)
        exitWithFailure()
      }
    }
  }

  def establishManualBspConnection(useTcp: Boolean): Unit = {
    import scala.util.Try
    def establishSocketConnection(useTcp: Boolean): Try[java.net.Socket] = {
      import org.scalasbt.ipcsocket.UnixDomainSocket
      Try {
        if (useTcp || isWindows) {
          val randomPort = portNumberWithin(17812, 18222)
          new java.net.Socket("127.0.0.1", randomPort)
        } else {
          val socketPath = tempDir.resolve(s"bsp.socket").toAbsolutePath.toString
          new UnixDomainSocket(socketPath)
        }
      }
    }

    val socketConnection = establishSocketConnection(useTcp).recoverWith {
      case NonFatal(t) =>
        Thread.sleep(250)
        t.printStackTrace(System.err)
        println("First connection failed, trying again...")
        establishSocketConnection(useTcp).recoverWith {
          case NonFatal(t) =>
            Thread.sleep(500)
            t.printStackTrace(System.err)
            println("Second connection failed, trying the last attempt...")
            establishSocketConnection(useTcp)
        }
    }

    socketConnection.flatMap { socket =>
      // Wire the streams to System.in and System.out
      System.setIn(socket.getInputStream)
      System.setOut(new java.io.PrintStream(socket.getOutputStream))

      // Start thread pumping stdin
      ???
    }
    ()
  }

  def recoverFromUninstalledServer(bloopVersion: String): ServerState = {
    import java.io.File
    import scala.concurrent.ExecutionContext.Implicits.global
    import coursier._
    import coursier.util.{Task, Gather}

    def resolveServer(withScalaSuffix: Boolean): (Dependency, Resolution) = {
      val moduleName = if (withScalaSuffix) name"bloop-frontend_2.12" else name"bloop-frontend"
      val bloopDependency = Dependency(Module(org"ch.epfl.scala", moduleName), bloopVersion)
      val start = Resolution(Set(bloopDependency))

      val repositories = Seq(
        Cache.ivy2Local,
        MavenRepository("https://repo1.maven.org/maven2"),
        MavenRepository("https://oss.sonatype.org/content/repositories/staging/"),
        MavenRepository("https://dl.bintray.com/scalacenter/releases/"),
        MavenRepository("https://dl.bintray.com/scalameta/maven/")
      )

      val fetch = Fetch.from(repositories, Cache.fetch[Task]())
      (bloopDependency, start.process.run(fetch).unsafeRun())
    }

    def fetchJars(r: Resolution): Seq[Path] = {
      val localArtifacts: Seq[Either[FileError, File]] =
        Gather[Task].gather(r.artifacts().map(Cache.file[Task](_).run)).unsafeRun()
      val fileErrors = localArtifacts.collect { case Left(error) => error }
      if (fileErrors.isEmpty) {
        localArtifacts.collect { case Right(f) => f }.map(_.toPath)
      } else {
        val prettyFileErrors = fileErrors.map(_.describe).mkString("\n")
        val errorMsg = s"Fetch error(s):\n${prettyFileErrors.mkString("\n")}"
        printError(errorMsg)
        exitWithFailureAndPrintInstallationInstructions()
      }
    }

    def installBloopBinaryInHomeDir: Option[ServerState] = {
      import java.net.URL
      import java.nio.channels.Channels
      import java.io.FileOutputStream

      val website = new URL(
        s"https://github.com/scalacenter/bloop/releases/download/v${bloopVersion}/install.py"
      )

      import scala.util.{Try, Success, Failure}
      val installpyPath = Try {
        val target = tempDir.resolve("install.py")
        val targetPath = target.toAbsolutePath.toString
        val channel = Channels.newChannel(website.openStream())
        val fos = new FileOutputStream(targetPath)
        val bytesTransferred = fos.getChannel().transferFrom(channel, 0, Long.MaxValue)

        // The file should already be created, make it executable so that we can run it
        target.toFile.setExecutable(true)
        targetPath
      }

      installpyPath match {
        case Success(targetPath) =>
          // Run the installer without a timeout (no idea how much it can last)
          val installCmd = List("python", targetPath)
          val installStatus = Utils.runCommand(installCmd, None)
          if (installStatus.isOk) {
            // We've just installed bloop in `$HOME/.bloop`, let's now detect the installation
            detectServerState(bloopVersion)
          } else {
            printError(s"Failed to run '${installCmd.mkString(" ")}'")
            printQuoted(installStatus.output)
            None
          }

        case Failure(NonFatal(t)) =>
          t.printStackTrace(System.err)
          printError(s"^ An error happened when downloading installer ${website}...")
          println("The launcher will now try to resolve and run the build server")
          None
        case Failure(t) => throw t // Throw non-fatal exceptions
      }
    }

    println(s"Bloop is not available in the machine, installing bloop ${bloopVersion}")
    installBloopBinaryInHomeDir.getOrElse {
      val (bloopDependency, vanillaResolution) = resolveServer(true)
      if (vanillaResolution.errors.isEmpty) {
        ResolvedAt(fetchJars(vanillaResolution))
      } else {
        // Before failing, let's try resolving bloop without a scala suffix to support future versions
        val (_, versionlessResolution) = resolveServer(false)
        if (versionlessResolution.errors.isEmpty) {
          ResolvedAt(fetchJars(versionlessResolution))
        } else {
          // Only report errors coming from the first resolution (second resolution was a backup)
          val prettyErrors = vanillaResolution.errors.map {
            case ((module, version), errors) =>
              s"  $module:$version\n${errors.map("    " + _.replace("\n", "    \n")).mkString("\n")}"
          }

          printError(s"Failed to resolve '${bloopDependency}'... the server cannot start!")
          val errorMsg = s"Resolution error:\n${prettyErrors.mkString("\n")}"
          exitWithFailureAndPrintInstallationInstructions()
        }
      }
    }
  }

  // Reused across the two different ways we can run a server
  private var serverStatusCommand: Option[(String, Utils.StatusCommand)] = None

  def startServerViaScript(binary: String): Unit = {
    startServerThread {
      // Running 'bloop server' should always work if v > 1.1.0
      val startCmd = List(binary, "server")
      println(s"Starting the bloop server with '${startCmd.mkString(" ")}'")
      val status = Utils.runCommand(startCmd, None)

      // Communicate to the driver logic in `connectToServer` if server failed or not
      serverStatusCommand = Some(startCmd.mkString(" ") -> status)

      ()
    }
  }

  def startServerViaClasspath(classpath: Seq[Path]): Unit = {
    startServerThread {
      // Running 'bloop server' should always work if v > 1.1.0
      val stringClasspath = classpath.map(_.normalize().toAbsolutePath).mkString(":")
      // NOTE: We don't need to support `$HOME/.bloop/.jvmopts` b/c `$HOME/.bloop` doesn't exist
      val startCmd = List("java", "-classpath", stringClasspath, "bloop.Server")
      println(s"Starting the bloop server with '${startCmd.mkString(" ")}'")
      val status = Utils.runCommand(startCmd, None)

      // Communicate to the driver logic in `connectToServer` if server failed or not
      serverStatusCommand = Some(startCmd.mkString(" ") -> status)

      ()
    }
  }

  def startServerThread(thunk: => Unit): Unit = {
    val serverThread = new Thread {
      override def run(): Unit = thunk
    }

    serverThread.setName("bsp-server-driver")
    // Extremely important so that future invocations can reuse the same server
    serverThread.setDaemon(true)
    serverThread.start()
  }

  private val shutdownSignalsPerThreadId: mutable.Map[Long, Boolean] = mutable.Map.empty
  def redirectStdinTo(process: NuProcess): Unit = {
    val backgroundThread = new Thread {
      private val in = System.in
      private val id = this.getId
      shutdownSignalsPerThreadId.+=(id -> false)
      override def run(): Unit = {
        // Redirect input in chunks of 128 (to ensure that messages are not buffered)
        val buffer = new Array[Byte](128)
        val read = in.read(buffer, 0, buffer.length)
        if (read == -1 || shutdownSignalsPerThreadId(id) || !process.isRunning) ()
        else process.writeStdin(ByteBuffer.wrap(buffer))
      }
    }

    backgroundThread.setName("bloop-bsp-launcher-stdin")
    backgroundThread.start()
  }

  def shutdownAllNonDaemonThreads(): Unit = {
    // Make sure that any thread pumping stdin running in the background is stopped
    shutdownSignalsPerThreadId.keysIterator.foreach { threadId =>
      shutdownSignalsPerThreadId.update(threadId, true)
    }
  }

  def exitWithSuccess(): Nothing = {
    shutdownAllNonDaemonThreads()
    sys.exit(0)
  }

  def exitWithFailure(): Nothing = {
    shutdownAllNonDaemonThreads()
    sys.exit(1)
  }

  def exitWithFailureAndPrintInstallationInstructions(): Nothing = {
    println(
      """Bloop was not found in your machine and the resolution of the build server failed.
        |Check that you're connected to the Internet and try again.
        |
        |You can also install bloop manually by following the installations instructions in:
        |  -> https://scalacenter.github.io/bloop/setup
        |
        |The installed version will be used by this launcher automatically if bloop is available
        |in your `$PATH` or installed in your `HOME/.bloop` directory. The launcher will also
        |connect
      """.stripMargin
    )
    exitWithFailure()
  }
}
