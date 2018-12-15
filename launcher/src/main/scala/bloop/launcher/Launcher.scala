package bloop.launcher

import java.io._
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file._

import io.github.soc.directories.ProjectDirectories

object Launcher extends LauncherMain(System.err, Shell(), nailgunPort = None)
class LauncherMain(val out: PrintStream, val shell: Shell, val nailgunPort: Option[Int]) {
  private val launcherTmpDir = Files.createTempDirectory(s"bsp-launcher")
  private val isWindows: Boolean = scala.util.Properties.isWin
  private val homeDirectory: Path = Paths.get(System.getProperty("user.home"))
  private val projectDirectories: ProjectDirectories =
    ProjectDirectories.from("", "", "bloop")
  private val bloopDataLogsDir: Path =
    Files.createDirectories(Paths.get(projectDirectories.dataDir).resolve("logs"))

  // TODO: Should we run this? How does this interact with current processes?
/*  Runtime.getRuntime.addShutdownHook(new Thread {
    override def run(): Unit = {
      deleteRecursively(launcherTmpDir)
    }
  })*/

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

  // TODO: Add a --bsp/--no-bsp mode to enable bsp connection or just start server
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
    println("Starting the bsp launcher for bloop...")
    connectToServer(bloopVersionToInstall) match {
      case Some(socket) =>
        wireBspConnectionStreams(socket)
        exitWithSuccess(true)
      case None =>
        printErrorReport()
        exitWithSuccess(false)
    }
  }

  def connectToServer(bloopVersion: String): Option[Socket] = {
    def checkBspSessionIsLive[T](establishConnection: => Option[T]): Option[T] = {
      bloopBackgroundError match {
        case Some((cmd, status)) if status.isOk =>
          printError(s"Unexpected successful early exit of the bsp server with '$cmd'!")
          if (!status.output.isEmpty) printQuoted(status.output)
          None

        case Some((cmd, status)) =>
          printError(s"Failed to start bloop bsp server with '$cmd'!")
          if (!status.output.isEmpty) printQuoted(status.output)
          None

        case None => establishConnection
      }
    }

    def waitForCLIToStart(): Unit = {
      val waitMs = 2000 //if (isWindows) 300 else 100
      // Sleep a little bit to give some time to the server to start up
      println(s"Embedded CLI started, waiting for ${waitMs}ms until it's up and running...")
      Thread.sleep(waitMs.toLong)
    }

    detectServerState(bloopVersion)
      .orElse(recoverFromUninstalledServer(bloopVersion))
      .flatMap {
        case ListeningAndAvailableAt(binary) =>
          establishBspConnectionViaBinary(binary, useTcp = false)
            .flatMap(c => connectToOpenSession(c))

        case AvailableAt(binary) =>
          // Start the server if we only have a bloop binary
          startServerViaScriptInBackground(binary)
          println("Server was started in a thread, waiting until it's up and running...")

          // Run `bloop about` until server is running for a max of 5 attempts
          var attempts: Int = 1
          var totalMs: Int = 0
          var listening: Option[ServerState] = None
          while ({
            listening match {
              case Some(ListeningAndAvailableAt(_)) => false
              case _ if attempts <= 5 => true
              case _ => false
            }
          }) {
            val waitMs = if (attempts <= 2) 200 else 500
            totalMs += waitMs
            println(s"Sleeping for ${waitMs}ms until we run `bloop about` to check server")
            listening = shell.runBloopAbout(binary ++ bloopAdditionalArgs)
            attempts += 1
          }

          listening match {
            case Some(state) =>
              // After the server is open, let's open a bsp connection
              establishBspConnectionViaBinary(binary, useTcp = false)
                .flatMap(c => connectToOpenSession(c))

            case None =>
              // Let's diagnose why `bloop about` failed to run for more than 5 attempts
              checkBspSessionIsLive {
                printError(
                  s"Failed to connect to the server with `bloop about` for a total waiting time of ${totalMs}ms")
                println("Check that the server is running or try again...")
                None
              }
          }

        // The build server is resolved when `install.py` failed in the system
        case ResolvedAt(classpath) =>
          val connection = runEmbeddedBspInvocationInBackground(classpath, false)
          waitForCLIToStart()

          // If after wait command failed, force tcp in case local connection is problematic
          if (bloopBackgroundError.isEmpty) {
            checkBspSessionIsLive {
              connectToOpenSession(connection)
            }
          } else {
            val connection2 = runEmbeddedBspInvocationInBackground(classpath, true)
            waitForCLIToStart()
            checkBspSessionIsLive {
              connectToOpenSession(connection2)
            }
          }
      }
  }

  def defaultBloopDirectory: Path = homeDirectory.resolve(".bloop")
  def detectServerState(bloopVersion: String): Option[ServerState] = {
    shell.detectBloopInSystemPath(List("bloop") ++ bloopAdditionalArgs).orElse {
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
          shell.detectBloopInSystemPath(List(binaryInHome) ++ bloopAdditionalArgs)
        }
      }
    }
  }

  private val alreadyInUseMsg = "Address already in use"

  // TODO: Improve to enable repeated local connections in case the server is not yet up and running

  sealed trait OpenBspConnection
  final case object OpenTcpConnection extends OpenBspConnection
  final case object OpenLocalConnection extends OpenBspConnection

  /**
   * Establish a bsp connection by telling the backtground server to open a BSP session.
   *
   * This routine is only called when a background server is running. The retry logic
   * and the waits are done because the server might be still starting up and the
   * bsp command may fail.
   *
   * The bsp connection will be attempted 5 times with several delays in between.
   * When more than 3 local connections have failed in non Windows systems, we use TCP
   * for the two remaining. After the 5 attempts, we just fail.
   *
   * @param bloopServerCmd The command we use to run
   * @param useTcp
   * @param attempts
   * @return
   */
  def establishBspConnectionViaBinary(
      bloopServerCmd: List[String],
      useTcp: Boolean,
      attempts: Int = 1
  ): Option[OpenBspConnection] = {
    var status: Option[shell.StatusCommand] = None
    val bspCmd: List[String] = shell.deriveBspInvocation(bloopServerCmd, useTcp, launcherTmpDir)
    val thread = new Thread {
      override def run(): Unit = {
        // Whenever the connection is broken or the server legitimately stops, this returns
        status = Some {
          shell.runCommand(
            bspCmd,
            Some(0),
          )
        }
      }
    }

    thread.start()

    // We wait until the OS has got an opportunity to run our thread
    Thread.sleep(50)

    def printStatus(status: shell.StatusCommand): Unit = {
      if (status.isOk) {
        printError(s"The command ${bspCmd.mkString(" ")} returned with a successful code")
      } else {
        printError(s"The command ${bspCmd.mkString(" ")} returned with an error")
      }

      printQuoted(status.output)
    }

    status match {
      // The connection is established (the process hasn't finished), proceed
      case None => if (useTcp) Some(OpenTcpConnection) else Some(OpenLocalConnection)
      case Some(status) if attempts == 2 =>
        printStatus(status)
        println(s"The command ${bspCmd.mkString(" ")} failed again, aborting...")
        None
      case Some(status) =>
        printStatus(status)

        // Try again, this time with tcp in case it's a problem of the local connection
        if (useTcp) None
        else {
          println("Try connecting to the server via TCP in case the local connection was a problem")
          establishBspConnectionViaBinary(bloopServerCmd, true, attempts + 1)
        }
    }
  }

  /**
   * Open a client session from the launcher when a bsp server session is available.
   *
   * A `bloop bsp` / embedded bsp cli invocation opens a bsp server session, but we
   * still need to connect to it so that we can redirect stdin/stdout as the BSP server
   * discovery protocol mandates.
   *
   * @param serverConnection An open bsp server connection.
   * @return A socket if the connection succeeded, none otherwise.
   */
  def connectToOpenSession(serverConnection: OpenBspConnection): Option[Socket] = {
    import scala.util.Try
    def establishSocketConnection(connection: OpenBspConnection): Try[Socket] = {
      import org.scalasbt.ipcsocket.UnixDomainSocket
      Try {
        connection match {
          case OpenTcpConnection =>
            val randomPort = shell.portNumberWithin(17812, 18222)
            new Socket("127.0.0.1", randomPort)
          case OpenLocalConnection =>
            val socketPath = launcherTmpDir.resolve(s"bsp.socket").toAbsolutePath.toString
            new UnixDomainSocket(socketPath)
        }
      }
    }

    establishSocketConnection(serverConnection) match {
      case scala.util.Success(socket) => Some(socket)
      case scala.util.Failure(t) =>
        printError("The bsp launcher couldn't open a socket to a bsp server session!")
        t.printStackTrace(out)
        None
    }
  }

  /**
   * Wires the client streams with the server streams so that they can communicate to each other.
   *
   * After a connection has been successfully established, we create two threads that will pump
   * contents in the streams in and out in a way that the server receives everything that we read
   * and the client receives everything we read from the server socket stream.
   *
   * This operation is the last thing the launcher does to set up a bsp connection. We don't care
   * if the operation succeeds or fails nor we have a way to know if an error is legit. If an error
   * happens we report it but return with a successful exit code.
   *
   * @param socket The socket connection established with the server.
   */
  def wireBspConnectionStreams(socket: Socket): Unit = {
    @volatile var sharedActive: Boolean = false

    def forwardInput(reader: BufferedReader, out: OutputStream): Unit = {
      var line: String = null
      // This is blocking, but will fail if any of the input streams is closed
      while ({ line = reader.readLine(); line != null }) {
        val bytes = line.getBytes(StandardCharsets.UTF_8)
        out.write(bytes)
        out.flush()
      }
    }

    val pumpStdinToSocketStdout = shell.startThread("bsp-client-to-server", false) {
      while (sharedActive) {
        val socketOut = socket.getOutputStream()
        val reader = new BufferedReader(new InputStreamReader(System.in))
        try forwardInput(reader, socketOut)
        catch {
          case e: IOException =>
            // Mark this as active so that we don't attempt more read/writes
            sharedActive = false
            println("Unexpected error when forwarding client stdin ---> server stdout")
            e.printStackTrace(out)
        } finally {
          reader.close()
          try socketOut.close()
          catch { case t: Throwable => () }
        }
      }
    }

    val pumpSocketStdinToStdout = shell.startThread("bsp-server-to-client", false) {
      while (sharedActive) {
        val reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))
        try forwardInput(reader, System.out)
        catch {
          case e: IOException =>
            // Mark this as active so that we don't attempt more read/writes
            sharedActive = false
            println("Unexpected exception when forwarding server stdin ---> client stdout")
            e.printStackTrace(out)
        } finally {
          reader.close()
          try System.out.close()
          catch { case t: Throwable => () }
        }
      }
    }

    // We block here, if the connection terminates the threads will
    pumpStdinToSocketStdout.join()
    pumpSocketStdinToStdout.join()

    () // The threads were terminated (either successfully or not, we don't care, return)
  }

  /**
   * Install the server by the universal method (install.py) or, if that fails, by resolving it.
   *
   * When the server is not installed, we need to figure out a way to bring it to the user's
   * computer. Therefore, we first try the `install.py` universal method to install bloop and
   * all its scripts. This method can fail if, for example, python is not in the classpath or
   * the installation script errors. As a fallback, we resolve bloop with coursier and execute
   * an embedded cli as the last attempt to obtain a bloop instance running. This method does
   * not depend on bloop, it just requires internet connection.
   *
   * @param bloopVersion The version we want to install, passed in by the client.
   * @return An optional server state depending on whether any of the installation method succeeded.
   */
  def recoverFromUninstalledServer(bloopVersion: String): Option[ServerState] = {
    println(s"Bloop is not available in the machine, installing bloop ${bloopVersion}")
    val fullyInstalled = Installer.installBloopBinaryInHomeDir(
      launcherTmpDir,
      defaultBloopDirectory,
      bloopVersion,
      out,
      detectServerState(_),
      shell
    )

    fullyInstalled.orElse {
      val (bloopDependency, vanillaResolution) = Installer.resolveServer(bloopVersion, true)
      if (vanillaResolution.errors.isEmpty) {
        val jars = Installer.fetchJars(vanillaResolution, out)
        if (jars.isEmpty) None else Some(ResolvedAt(jars))
      } else {
        // Before failing, let's try resolving bloop without a scala suffix to support future versions
        val (_, versionlessResolution) = Installer.resolveServer(bloopVersion, false)
        if (versionlessResolution.errors.isEmpty) {
          val jars = Installer.fetchJars(versionlessResolution, out)
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
  private var bloopBackgroundError: Option[(String, shell.StatusCommand)] = None

  /**
   * Start a server in the background by using the python script `bloop server`.
   *
   * This operation can take a while in some operating systems (most notably Windows, Unix is fast).
   * After running a thread in the background, we will wait until the server is up.
   *
   * @param binary The list of arguments that make the python binary script we want to run.
   */
  def startServerViaScriptInBackground(binary: List[String]): Unit = {
    bloopBackgroundError = None
    // Always keep a server running in the background by making it a daemon thread
    shell.startThread("bsp-server-background", true) {
      // Running 'bloop server' should always work if v > 1.1.0
      val startCmd = binary ++ List("server")
      println(s"Starting the bloop server with '${startCmd.mkString(" ")}'")
      val status = shell.runCommand(startCmd, None)

      // Communicate to the driver logic in `connectToServer` if server failed or not
      bloopBackgroundError = Some(startCmd.mkString(" ") -> status)

      ()
    }
    ()
  }

  def runEmbeddedBspInvocationInBackground(
      classpath: Seq[Path],
      forceTcp: Boolean
  ): OpenBspConnection = {
    bloopBackgroundError = None
    val connection = if (isWindows || forceTcp) OpenTcpConnection else OpenLocalConnection
    // In the embedded mode, the cli bsp invocation is not a daemon
    shell.startThread("bsp-cli-embedded", false) {
      // NOTE: We don't need to support `$HOME/.bloop/.jvmopts` b/c `$HOME/.bloop` doesn't exist
      val stringClasspath = classpath.map(_.normalize().toAbsolutePath).mkString(":")
      val startCmd = List("java", "-classpath", stringClasspath, "bloop.Cli")
      val cmd = shell.deriveBspInvocation(startCmd, isWindows || forceTcp, launcherTmpDir)
      println(s"Starting the bloop server with '${cmd.mkString(" ")}'")

      val status = shell.runCommand(cmd, None)

      // Communicate to the driver logic in `connectToServer` if server failed or not
      bloopBackgroundError = Some(cmd.mkString(" ") -> status)

      ()
    }

    connection
  }

  def exitWithSuccess(): Unit = {
    sys.exit(0)
  }

  def exitWithFailure(): Unit = {
    sys.exit(1)
  }

  def printErrorReport(): Unit = {
    println(
      """------------------
        |-- Error Report --
        |------------------
        |
        |Bloop was not found in your machine and the resolution of the build server failed.
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
  }

  def deleteRecursively(directory: Path): Unit = {
    try {
      Files.walkFileTree(
        directory,
        new SimpleFileVisitor[Path]() {
          @Override
          override def visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult = {
            Files.delete(file)
            FileVisitResult.CONTINUE
          }

          override def postVisitDirectory(dir: Path, exc: IOException): FileVisitResult = {
            Files.delete(dir)
            FileVisitResult.CONTINUE
          }
        }
      )
      ()
    } catch {
      // Ignore any IO exception...
      case io: IOException => ()
    }
  }
}
