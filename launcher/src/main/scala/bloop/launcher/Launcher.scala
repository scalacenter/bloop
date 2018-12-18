package bloop.launcher

import java.io._
import java.net.Socket
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file._

import io.github.soc.directories.ProjectDirectories

import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise

object Launcher
    extends LauncherMain(
      System.in,
      System.out,
      System.err,
      StandardCharsets.UTF_8,
      Shell.default,
      nailgunPort = None,
      Promise[Unit]()
    )

class LauncherMain(
    clientIn: InputStream,
    clientOut: OutputStream,
    val out: PrintStream,
    charset: Charset,
    val shell: Shell,
    val nailgunPort: Option[Int],
    startedServer: Promise[Unit]
) {
  private final val BspStartLog = "The server is listening for incoming connections at"
  private final val launcherTmpDir = Files.createTempDirectory(s"bsp-launcher")
  private final val isWindows: Boolean = scala.util.Properties.isWin
  private final val homeDirectory: Path = Paths.get(System.getProperty("user.home"))
  private final val projectDirectories: ProjectDirectories =
    ProjectDirectories.from("", "", "bloop")
  private final val bloopDataLogsDir: Path =
    Files.createDirectories(Paths.get(projectDirectories.dataDir).resolve("logs"))

  private final val bloopAdditionalArgs: List[String] = nailgunPort match {
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
    val (jvmOptions, cliArgs) = args.span(_.startsWith("-J"))
    if (cliArgs.size == 1) {
      val bloopVersion = cliArgs.apply(0)
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
        startedServer.failure(new RuntimeException("The server was not started"))
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

    def openBspSocket(forceTcp: Boolean = false)(
        connect: Boolean => RunningBspConnection
    ): Option[Socket] = {
      val connection = connect(forceTcp)
      waitForOpenBsp(connection) match {
        case Some(c) => connectToOpenSession(c)
        case None =>
          connection match {
            case RunningBspConnection(OpenBspConnection.Tcp(_, _), _) =>
              checkBspSessionIsLive {
                printError("The launcher failed to establish a bsp connection, aborting...")
                None
              }

            case RunningBspConnection(connection, logs) =>
              printError("Trying a tcp-based connection to the server instead...")
              openBspSocket(true)(connect)
          }
      }
    }

    detectServerState(bloopVersion)
      .orElse(recoverFromUninstalledServer(bloopVersion))
      .flatMap {
        case ListeningAndAvailableAt(binary) =>
          openBspSocket(false) { useTcp =>
            establishBspConnectionViaBinary(binary, useTcp)
          }

        case AvailableAt(binary) =>
          // Start the server if we only have a bloop binary
          startServerViaScriptInBackground(binary)
          println("Server was started in a thread, waiting until it's up and running...")

          // Run `bloop about` until server is running for a max of N attempts
          val maxAttempts: Int = 20
          var attempts: Int = 1
          var totalMs: Long = 0
          var listening: Option[ServerState] = None
          while ({
            listening match {
              case Some(ListeningAndAvailableAt(_)) => false
              case _ if attempts <= maxAttempts => true
              case _ => false
            }
          }) {
            val waitMs = 300.toLong
            Thread.sleep(waitMs)
            totalMs += waitMs
            // In total, we wait about 6 seconds until the server starts...
            println(s"Sleeping for ${waitMs}ms until we run `bloop about` to check server")
            listening = shell.runBloopAbout(binary, out)
            attempts += 1
          }

          listening match {
            case Some(ListeningAndAvailableAt(binary)) =>
              openBspSocket(false) { forceTcp =>
                establishBspConnectionViaBinary(binary, forceTcp)
              }

            case _ =>
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
          openBspSocket(false) { forceTcp =>
            runEmbeddedBspInvocationInBackground(classpath, forceTcp)
          }
      }
  }

  def defaultBloopDirectory: Path = homeDirectory.resolve(".bloop")
  def detectServerState(bloopVersion: String): Option[ServerState] = {
    shell.detectBloopInSystemPath(List("bloop") ++ bloopAdditionalArgs, out).orElse {
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
          shell.detectBloopInSystemPath(List(binaryInHome) ++ bloopAdditionalArgs, out)
        }
      }
    }
  }

  private val alreadyInUseMsg = "Address already in use"
  private var bspServerStatus: Option[(String, shell.StatusCommand)] = None

  def resetServerStatus(): Unit = {
    bspServerStatus = None
  }

  case class RunningBspConnection(bsp: OpenBspConnection, logs: ListBuffer[String])

  /**
   * Establish a bsp connection by telling the background server to open a BSP session.
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
   * @param useTcp Whether TCP should be always used or not.
   * @param attempts How many attempts are allowed before failing to establish a connection.
   * @return An open bsp connection that we need to poll.
   */
  def establishBspConnectionViaBinary(
      bloopServerCmd: List[String],
      useTcp: Boolean,
      attempts: Int = 1
  ): RunningBspConnection = {
    // Reset the status as it can be called several times
    resetServerStatus()

    val logsBuffer = new ListBuffer[String]()
    val (bspCmd, openConnection) = shell.deriveBspInvocation(bloopServerCmd, useTcp, launcherTmpDir)
    println(s"Opening a bsp server connection with ${bspCmd.mkString(" ")}")
    val thread = new Thread {
      override def run(): Unit = {
        // Whenever the connection is broken or the server legitimately stops, this returns
        bspServerStatus = Some {
          bspCmd.mkString(" ") -> {
            shell.runCommand(
              // Make it verbose so that bsp preparation logs are emitted
              bspCmd ++ List("--verbose"),
              None,
              Some(logsBuffer)
            )
          }
        }
      }
    }

    thread.start()
    RunningBspConnection(openConnection, logsBuffer)
  }

  def runEmbeddedBspInvocationInBackground(
      classpath: Seq[Path],
      forceTcp: Boolean
  ): RunningBspConnection = {
    // Reset the status as it can be called several times
    resetServerStatus()

    // NOTE: We don't need to support `$HOME/.bloop/.jvmopts` b/c `$HOME/.bloop` doesn't exist
    val stringClasspath = classpath.map(_.normalize().toAbsolutePath).mkString(":")
    val (cmd, connection) = {
      val startCmd = List("java", "-classpath", stringClasspath, "bloop.Cli")
      shell.deriveBspInvocation(startCmd, forceTcp, launcherTmpDir)
    }

    val logsBuffer = new ListBuffer[String]()

    // In the embedded mode, the cli bsp invocation is not a daemon
    shell.startThread("bsp-cli-embedded", false) {
      println(s"Starting the bloop server with '${cmd.mkString(" ")}'")
      val status = shell.runCommand(
        cmd ++ List("--verbose"),
        None,
        Some(logsBuffer)
      )

      // Communicate to the driver logic in `connectToServer` if server failed or not
      bspServerStatus = Some(cmd.mkString(" ") -> status)
    }

    RunningBspConnection(connection, logsBuffer)
  }

  def waitForOpenBsp(conn: RunningBspConnection, attempts: Int = 0): Option[OpenBspConnection] = {
    println("Waiting 200ms until the bsp connection is up...")
    Thread.sleep(200)
    if (attempts == 25) {
      printError("Giving up on waiting for a connection, printing embedded bloop logs:")
      printQuoted(conn.logs.toList.mkString(System.lineSeparator()))
      None
    } else if (conn.logs.exists(_.contains(BspStartLog))) {
      Some(conn.bsp)
    } else {
      bspServerStatus match {
        // Recurse only if the bsp server seems up...
        case None => waitForOpenBsp(conn, attempts + 1)
        case Some((cmd, status)) =>
          if (status.isOk) {
            printError(s"The command $cmd returned too early with a successful code")
          } else {
            printError(s"The command $cmd returned with an error")
          }

          printQuoted(status.output)
          None
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
          case OpenBspConnection.Tcp(host, port) => new Socket(host, port)
          case OpenBspConnection.UnixLocal(socketPath) =>
            new UnixDomainSocket(socketPath.toAbsolutePath.toString)
        }
      }
    }

    establishSocketConnection(serverConnection) match {
      case scala.util.Success(socket) => Some(socket)
      case scala.util.Failure(t) =>
        printError("The launcher couldn't open a socket to a bsp server session!")
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
    @volatile var isConnectionOpen: Boolean = true

    def closeInconditionally(c: Closeable): Unit = {
      try c.close()
      catch { case t: Throwable => () }
    }

    println("Starting thread that pumps stdin and redirects it to the bsp server...")
    val pumpStdinToSocketStdout = shell.startThread("bsp-client-to-server", false) {
      while (isConnectionOpen) {
        val socketOut = socket.getOutputStream()
        val parser = new JsonRpcParser(out, StandardCharsets.US_ASCII)
        try {
          parser.forward(clientIn, socketOut)
          isConnectionOpen = false
          println("No more data in the client stdin, exiting...")
        } catch {
          case e: IOException =>
            if (isConnectionOpen) {
              // Mark this as active so that we don't attempt more read/writes
              isConnectionOpen = false
              printError("Unexpected error when forwarding client stdin ---> server stdout")
              e.printStackTrace(out)
            }
        } finally {
          closeInconditionally(clientIn)
          closeInconditionally(socketOut)
        }
      }
    }

    println("Starting thread that pumps server stdout and redirects it to the client stdout...")
    val pumpSocketStdinToStdout = shell.startThread("bsp-server-to-client", false) {
      while (isConnectionOpen) {
        val socketIn = socket.getInputStream()
        val parser = new JsonRpcParser(out, StandardCharsets.US_ASCII)
        try {
          parser.forward(socketIn, clientOut)
          isConnectionOpen = false
          println("No more data in the server stdin, exiting...")
        } catch {
          case e: IOException =>
            if (isConnectionOpen) {
              // Mark this as active so that we don't attempt more read/writes
              isConnectionOpen = false
              println("Unexpected exception when forwarding server stdin ---> client stdout")
              e.printStackTrace(out)
            }
        } finally {
          closeInconditionally(socketIn)
          closeInconditionally(clientOut)
        }
      }
    }

    if (isConnectionOpen) {
      // We need to complete the promise immediately after the threads are started
      startedServer.success(())

      // We block here, if the connection terminates the threads will
      pumpStdinToSocketStdout.join()
      pumpSocketStdinToStdout.join()
    }

    () // The threads were terminated (either successfully or not, we don't care, return)
  }

  /**
   * Checks that a bloop version can be used with the launcher.
   * Compatible bloop versions are those that are the same or bigger than 1.1.2.
   *
   * @param version The bloop version we want to install if it's missing.
   * @return Whether the version in compatible or not depending on if it can be parsed or not.
   */
  def isValidBloopVersion(version: String): Option[Boolean] = {
    import coursier.core.Version
    Version(version).items match {
      case Version.Number(major) :: Version.Number(minor) :: Version.Number(patch) :: xs =>
        Some(major >= 1 && minor >= 1 && patch >= 2)
      case _ =>
        printError(s"Bloop version ${version} does not follow semantic versioning")
        None
    }
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
    isValidBloopVersion(bloopVersion) match {
      case Some(true) =>
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
            // Before failing, let's try resolving bloop without a scala suffix to support future versions out of the box
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

      case _ => None
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
    // Always keep a server running in the background by making it a daemon thread
    shell.startThread("bsp-server-background", true) {
      // Running 'bloop server' should always work if v > 1.1.0
      val startCmd = nailgunPort match {
        case Some(port) => binary.diff(bloopAdditionalArgs) ++ List("server", port.toString)
        case None => binary ++ List("server")
      }

      println(s"Starting the bloop server with '${startCmd.mkString(" ")}'")
      val status = shell.runCommand(startCmd, None)

      // Communicate to the driver logic in `connectToServer` if server failed or not
      bloopBackgroundError = Some(startCmd.mkString(" ") -> status)

      ()
    }

    ()
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
}
