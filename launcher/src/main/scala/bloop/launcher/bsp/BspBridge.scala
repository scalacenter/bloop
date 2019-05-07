package bloop.launcher.bsp

import java.io.{Closeable, IOException, InputStream, OutputStream, PrintStream}
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Path

import bloop.launcher.core.{Feedback, Shell}
import bloop.launcher.util.Environment
import bloop.launcher.{printError, printQuoted, println}
import bloop.sockets.UnixDomainSocket

import scala.collection.mutable.ListBuffer
import scala.concurrent.Promise

final class BspBridge(
    clientIn: InputStream,
    clientOut: OutputStream,
    startedServer: Promise[Unit],
    out: PrintStream,
    shell: Shell,
    launcherTmpDir: Path
) {
  private val alreadyInUseMsg = "Address already in use"
  private var bspServerStatus: Option[(String, shell.StatusCommand)] = None

  def resetServerStatus(): Unit = {
    bspServerStatus = None
  }

  case class RunningBspConnection(bsp: BspConnection, logs: ListBuffer[String])

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
    println(Feedback.openingBspConnection(bspCmd), out)
    val thread = new Thread {
      override def run(): Unit = {
        // Whenever the connection is broken or the server legitimately stops, this returns
        bspServerStatus = Some {
          bspCmd.mkString(" ") -> {
            shell.runCommand(
              // Make it verbose so that bsp preparation logs are emitted
              bspCmd ++ List("--verbose"),
              Environment.cwd,
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
      forceTcp: Boolean,
      jvmOptions: List[String]
  ): RunningBspConnection = {
    // Reset the status as it can be called several times
    resetServerStatus()

    // NOTE: We don't need to support `$HOME/.bloop/.jvmopts` b/c `$HOME/.bloop` doesn't exist
    val delimiter = if (Environment.isWindows) ";" else ":"
    val stringClasspath = classpath.map(_.normalize().toAbsolutePath).mkString(delimiter)
    val (cmd, connection) = {
      val opts = jvmOptions.map(_.stripPrefix("-J"))
      val startCmd = List("java") ++ opts ++ List("-classpath", stringClasspath, "bloop.Cli")
      shell.deriveBspInvocation(startCmd, forceTcp, launcherTmpDir)
    }

    val logsBuffer = new ListBuffer[String]()

    // In the embedded mode, the cli bsp invocation is not a daemon
    shell.startThread("bsp-cli-embedded", false) {
      println(Feedback.startingBloopServer(cmd), out)
      val status = shell.runCommand(
        cmd ++ List("--verbose"),
        Environment.cwd,
        None,
        Some(logsBuffer)
      )

      // Communicate to the driver logic in `connectToServer` if server failed or not
      bspServerStatus = Some(cmd.mkString(" ") -> status)
    }

    RunningBspConnection(connection, logsBuffer)
  }

  private final val BspStartLog = "The server is listening for incoming connections at"
  def waitForOpenBsp(conn: RunningBspConnection, attempts: Int = 0): Option[BspConnection] = {
    println("Waiting 200ms until the bsp connection is up...", out)
    Thread.sleep(200)
    if (attempts == 50) {
      printError("Giving up on waiting for a connection, printing embedded bloop logs:", out)
      printQuoted(conn.logs.toList.mkString(System.lineSeparator()), out)
      None
    } else if (conn.logs.exists(_.contains(BspStartLog))) {
      Some(conn.bsp)
    } else {
      bspServerStatus match {
        // Recurse only if the bsp server seems up...
        case None => waitForOpenBsp(conn, attempts + 1)
        case Some((cmd, status)) =>
          if (status.isOk) {
            printError(s"The command $cmd returned too early with a successful code", out)
          } else {
            printError(s"The command $cmd returned with an error", out)
          }

          printQuoted(status.output, out)
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
  def connectToOpenSession(serverConnection: BspConnection): Option[Socket] = {
    import scala.util.Try
    def establishSocketConnection(connection: BspConnection): Try[Socket] = {
      Try {
        connection match {
          case BspConnection.Tcp(host, port) => new Socket(host, port)
          case BspConnection.UnixLocal(socketPath) =>
            new UnixDomainSocket(socketPath.toAbsolutePath.toString)
        }
      }
    }

    establishSocketConnection(serverConnection) match {
      case scala.util.Success(socket) => Some(socket)
      case scala.util.Failure(t) =>
        printError("The launcher couldn't open a socket to a bsp server session!", out)
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

    println("Starting thread that pumps stdin and redirects it to the bsp server...", out)
    val pumpStdinToSocketStdout = shell.startThread("bsp-client-to-server", false) {
      var hasReportedClientError: Boolean = false
      while (isConnectionOpen) {
        val socketOut = socket.getOutputStream
        val parser = new JsonRpcParser(out, StandardCharsets.US_ASCII)
        try {
          parser.forward(clientIn, socketOut)
          isConnectionOpen = false
        } catch {
          case e: IOException =>
            if (isConnectionOpen) {
              // Mark this as active so that we don't attempt more read/writes
              hasReportedClientError = true
              isConnectionOpen = false
              printError("Unexpected error when forwarding client stdin ---> server stdout", out)
              e.printStackTrace(out)
            }
        } finally {
          closeInconditionally(clientIn)
          closeInconditionally(socketOut)
        }
      }

      if (!hasReportedClientError) {
        println("No more data in the client stdin, exiting...", out)
      }
    }

    println(
      "Starting thread that pumps server stdout and redirects it to the client stdout...",
      out
    )
    val pumpSocketStdinToStdout = shell.startThread("bsp-server-to-client", false) {
      var hasReportedServerError: Boolean = false
      while (isConnectionOpen) {
        val socketIn = socket.getInputStream
        val parser = new JsonRpcParser(out, StandardCharsets.US_ASCII)
        try {
          parser.forward(socketIn, clientOut)
          isConnectionOpen = false
          println("No more data in the server stdin, exiting...", out)
        } catch {
          case e: IOException =>
            if (isConnectionOpen) {
              hasReportedServerError = true
              // Mark this as active so that we don't attempt more read/writes
              isConnectionOpen = false
              println("Unexpected exception when forwarding server stdin ---> client stdout", out)
              e.printStackTrace(out)
            }
        } finally {
          closeInconditionally(socketIn)
          closeInconditionally(clientOut)
        }
      }

      if (!hasReportedServerError) {
        println("No more data in the server stdin, exiting...", out)
      }
    }

    if (isConnectionOpen) {
      // We need to complete the promise immediately after the threads are started
      startedServer.success(())

      // We block here, if the connection terminates the threads will
      pumpStdinToSocketStdout.join()

      try {
        pumpSocketStdinToStdout.interrupt()
        // Join this thread for 500ms before interrupting it
        pumpSocketStdinToStdout.join(500)
      } catch {
        case t: InterruptedException => ()
      }
    }
  }
}
