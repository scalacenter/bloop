package bloop.launcher

import java.io.PrintStream
import java.nio.ByteBuffer
import java.nio.file.{Files, Path, Paths}

import com.zaxxer.nuprocess.NuProcess
import io.github.soc.directories.ProjectDirectories

import scala.collection.mutable
import scala.util.control.NonFatal

object Launcher extends LauncherMain(System.err, nailgunPort = None)
class LauncherMain(val out: PrintStream, nailgunPort: Option[Int]) {
  private lazy val tempDir = Files.createTempDirectory(s"bsp-launcher")
  private val isWindows: Boolean = scala.util.Properties.isWin
  private val homeDirectory: Path = Paths.get(System.getProperty("user.home"))
  private val projectDirectories: ProjectDirectories =
    ProjectDirectories.from("", "", "bloop")
  private val bloopDataLogsDir: Path =
    Files.createDirectories(Paths.get(projectDirectories.dataDir).resolve("logs"))

  private val bloopAdditionalArgs: List[String] = nailgunPort match {
    case Some(port) => List("--nailgun-port", port.toString)
    case None => Nil
  }

  // Override print to redirect msgs to System.err (as mandated by the BSP spec)
  def print(msg: String): Unit = bloop.launcher.print(msg, out)
  def println(msg: String): Unit = bloop.launcher.println(msg, out)
  def printError(msg: String): Unit = bloop.launcher.println(s"error: ${msg}", out)
  def printQuoted(msg: String): Unit = {
    bloop.launcher.println(
      msg
        .split(System.lineSeparator())
        .map(l => s"> $l")
        .mkString(System.lineSeparator()),
      out
    )
  }

  def main(args: Array[String]): Unit = {
    cli(args, b => if (b) exitWithSuccess() else exitWithFailure())
  }

  def cli(args: Array[String], exitWithSuccess: Boolean => Unit): Unit = {
    // TODO: Add support of java arguments passed in to the launcher
    if (args.size == 1) {
      val bloopVersion = args.apply(0)
      runLauncher(bloopVersion, exitWithSuccess)
    } else {
      printError("The bloop launcher accepts only one argument: the bloop version.")
      exitWithSuccess(false)
    }
  }

  def runLauncher(bloopVersionToInstall: String, exitWithSuccess: Boolean => Unit): Unit = {
    println("Starting the bsp launcher for bloop")
    if (isPythonInClasspath) {
      printError("Python must be installed, it's not accessible via classpath")
      exitWithSuccess(connectToServer(bloopVersionToInstall))
    }
  }

  def isPythonInClasspath: Boolean = {
    Utils.runCommand(List("python", "--help"), Some(2)).isOk
  }

  def detectBloopInSystemPath(binary: String): Option[ServerState] = {
    // --nailgun-help is always interpreted in the script, no connection with the server is required
    val status = Utils.runCommand(List(binary, "--nailgun-help"), Some(10))
    if (!status.isOk) None
    else {
      // bloop is installed, let's check if it's running now
      val statusAbout = Utils.runCommand(List(binary, "about") ++ bloopAdditionalArgs, Some(10))
      Some {
        if (statusAbout.isOk) ListeningAndAvailableAt(binary)
        else AvailableAt(binary)
      }
    }
  }

  def connectToServer(bloopVersion: String): Boolean = {
    def handleServerState(establishConnection: => Boolean): Boolean = {
      serverStatusCommand match {
        case Some((cmd, status)) if status.isOk =>
          printError(s"Unexpected successful early exit of the bsp server with '$cmd'!")
          if (!status.output.isEmpty) printQuoted(status.output)
          false

        case Some((cmd, status)) =>
          printError(s"Failed to start bloop bsp server with '$cmd'!")
          if (!status.output.isEmpty) printQuoted(status.output)
          false

        case None => establishConnection
      }
    }

    val status = detectServerState(bloopVersion).orElse(recoverFromUninstalledServer(bloopVersion))
    status match {
      case Some(ListeningAndAvailableAt(binary)) =>
        // We don't use tcp by default, only if a local connection fails
        establishBspConnectionViaBinary(binary, false)

      case Some(AvailableAt(binary)) =>
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
      case Some(ResolvedAt(classpath)) =>
        startServerViaClasspath(classpath)

        // Sleep a little bit to give some time to the server to start up
        println("Server was started in a thread, waiting until it's up and running...")
        // We need to be less conservative with the java execution in Windows than the script above
        if (isWindows) Thread.sleep(500) else Thread.sleep(250)

        handleServerState {
          // The server cmd is still running, try connection
          establishManualBspConnection(false)
        }

      case None =>
        printInstallationInstructions()
        false
    }
  }

  def defaultBloopDirectory: Path = homeDirectory.resolve(".bloop")
  def detectServerState(bloopVersion: String): Option[ServerState] = {
    detectBloopInSystemPath("bloop").orElse {
      // The binary is not available in the classpath
      val homeBloopDir = defaultBloopDirectory
      if (!Files.exists(homeBloopDir)) None
      else {
        // This is the nailgun script that we can use to run bloop
        val binaryName = if (isWindows) "bloop.cmd" else "bloop"
        val pybloop = homeBloopDir.resolve(binaryName)
        if (!Files.exists(pybloop)) None
        else {
          val binaryInHome = pybloop.normalize.toAbsolutePath.toString
          detectBloopInSystemPath(binaryInHome)
        }
      }
    }
  }

  private val alreadyInUseMsg = "Address already in use"

  // TODO: Improve to enable repeated local connections in case the server is not yet up and running
  def establishBspConnectionViaBinary(
      binary: String,
      useTcp: Boolean,
      attempts: Int = 1
  ): Boolean = {
    val bspCmd: List[String] = {
      // For Windows, pick TCP until we fix https://github.com/scalacenter/bloop/issues/281
      if (useTcp || isWindows) {
        val randomPort = Utils.portNumberWithin(17812, 18222).toString
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
      true
    } else {
      if (status.output.contains(alreadyInUseMsg)) {
        if (attempts <= 5) establishBspConnectionViaBinary(binary, useTcp, attempts + 1)
        else {
          // Too many attempts, the connection seems off, let's report
          printError(s"Received '${alreadyInUseMsg}' > 5 times when attempting connection...")
          printError(s"Printing the output of the last invocation: ${bspCmd.mkString(" ")}")
          printQuoted(status.output)
          false
        }
      } else {
        printError(s"Failed to establish bsp connection with ${bspCmd.mkString(" ")}")
        printQuoted(status.output)

        println("\nTrying connection via TCP before failing...")
        establishBspConnectionViaBinary(binary, true, attempts + 1)
      }
    }
  }

  def establishManualBspConnection(useTcp: Boolean): Boolean = {
    import scala.util.Try
    def establishSocketConnection(useTcp: Boolean): Try[java.net.Socket] = {
      import org.scalasbt.ipcsocket.UnixDomainSocket
      Try {
        if (useTcp || isWindows) {
          val randomPort = Utils.portNumberWithin(17812, 18222)
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
        t.printStackTrace(out)
        println("First connection failed, trying again...")
        establishSocketConnection(useTcp).recoverWith {
          case NonFatal(t) =>
            Thread.sleep(500)
            t.printStackTrace(out)
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

    false
  }

  def recoverFromUninstalledServer(bloopVersion: String): Option[ServerState] = {
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
        Nil
      }
    }

    println(s"Bloop is not available in the machine, installing bloop ${bloopVersion}")
    val fullyInstalled = Installer.installBloopBinaryInHomeDir(
      tempDir,
      defaultBloopDirectory,
      bloopVersion,
      out,
      detectServerState(_)
    )

    fullyInstalled.orElse {
      val (bloopDependency, vanillaResolution) = resolveServer(true)
      if (vanillaResolution.errors.isEmpty) {
        val jars = fetchJars(vanillaResolution)
        if (jars.isEmpty) None else Some(ResolvedAt(jars))
      } else {
        // Before failing, let's try resolving bloop without a scala suffix to support future versions
        val (_, versionlessResolution) = resolveServer(false)
        if (versionlessResolution.errors.isEmpty) {
          val jars = fetchJars(versionlessResolution)
          if (jars.isEmpty) None else Some(ResolvedAt(jars))
        } else {
          // Only report errors coming from the first resolution (second resolution was a backup)
          val prettyErrors = vanillaResolution.errors.map {
            case ((module, version), errors) =>
              s"  $module:$version\n${errors.map("    " + _.replace("\n", "    \n")).mkString("\n")}"
          }

          printError(s"Failed to resolve '${bloopDependency}'... the server cannot start!")
          val errorMsg = s"Resolution error:\n${prettyErrors.mkString("\n")}"
          None
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

  def exitWithSuccess(): Unit = {
    shutdownAllNonDaemonThreads()
    sys.exit(0)
  }

  def exitWithFailure(): Unit = {
    shutdownAllNonDaemonThreads()
    sys.exit(1)
  }

  def printInstallationInstructions(): Unit = {
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
