package bloop.launcher.bsp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.concurrent.Promise

import bloop.bloopgun.BloopgunCli
import bloop.bloopgun.core.Shell
import bloop.bloopgun.core.Shell.StatusCommand
import bloop.bloopgun.util.Environment
import bloop.launcher.core.Feedback
import bloop.launcher.printError
import bloop.launcher.printQuoted
import bloop.launcher.println
import bloop.sockets.UnixDomainSocket

final class BspBridge(
    clientIn: InputStream,
    clientOut: OutputStream,
    startedServer: Promise[Unit],
    out: PrintStream,
    shell: Shell,
    launcherTmpDir: Path
) {
  private var bspServerStatus: Option[(String, StatusCommand)] = None

  def resetServerStatus(): Unit = {
    bspServerStatus = None
  }

  case class RunningBspConnection(bsp: BspConnection, out: BspBridge.LogsRecordingStream) {
    def logs: Seq[String] = out.logs
  }

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
   * @param useTcp Whether TCP should be always used or not.
   * @param attempts How many attempts are allowed before failing to establish a connection.
   * @return An open bsp connection that we need to poll.
   */
  def establishBspConnectionViaBinary(
      createCli: PrintStream => BloopgunCli,
      bloopAdditionalArgs: List[String],
      useTcp: Boolean,
  ): RunningBspConnection = {
    // Reset the status as it can be called several times
    resetServerStatus()

    val cliOut = BspBridge.LogsRecordingStream(maxLines = 200)
    val cli = createCli(new PrintStream(cliOut))
    val (bspCmd, openConnection) = deriveBspInvocation(useTcp, launcherTmpDir)
    println(Feedback.openingBspConnection(bspCmd), out)
    val thread = new Thread {
      override def run(): Unit = {
        // Whenever the connection is broken or the server legitimately stops, this returns
        bspServerStatus = Some {
          bspCmd.mkString(" ") -> {
            // Make it verbose so that bsp preparation logs are emitted
            val args = bspCmd ++ bloopAdditionalArgs ++ List("--verbose")
            StatusCommand(cli.run(args.toArray), "")
          }
        }
      }
    }

    thread.start()
    RunningBspConnection(openConnection, cliOut)
  }

  def deriveBspInvocation(
      useTcp: Boolean,
      tempDir: Path
  ): (List[String], BspConnection) = {
    // For Windows, pick TCP until we fix https://github.com/scalacenter/bloop/issues/281
    if (useTcp || Environment.isWindows) {
      // We draw a random port from a "safe" tcp port range...
      val randomPort = Shell.portNumberWithin(11322, 45411)
      val cmd = List("bsp", "--protocol", "tcp", "--port", randomPort.toString)
      (cmd, BspConnection.Tcp("127.0.0.1", randomPort))
    } else {
      // Let's be conservative with names here, socket files have a 100 char limit
      val socketPath = tempDir.resolve(s"bsp.socket").toAbsolutePath
      Files.deleteIfExists(socketPath)
      val cmd = List("bsp", "--protocol", "local", "--socket", socketPath.toString)
      (cmd, BspConnection.UnixLocal(socketPath))
    }
  }

  private final val BspStartLog = "The server is listening for incoming connections at"
  def waitForOpenBsp(conn: RunningBspConnection, attempts: Int = 0): Option[BspConnection] = {
    def printLogs = printQuoted(conn.logs.toList.mkString(System.lineSeparator()), out)
    if (attempts % 100 == 0) println("Waiting for the bsp connection to come up...", out)
    Thread.sleep(10)
    if (attempts == 3000) {
      printError("Giving up on waiting for a connection, printing embedded bloop logs:", out)
      printLogs
      None
    } else if (conn.logs.exists(_.contains(BspStartLog))) {
      println(conn.logs.mkString(System.lineSeparator), out)
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
          printLogs
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

    def closeUnconditionally(c: Closeable): Unit = {
      try c.close()
      catch { case _: Throwable => () }
    }

    println("Starting thread that pumps stdin and redirects it to the bsp server...", out)
    val pumpStdinToSocketStdout = shell.startThread("bsp-client-to-server", true) {
      var hasReportedClientError: Boolean = false
      while (isConnectionOpen) {
        val socketOut = socket.getOutputStream
        try {
          forwardStreamContents(clientIn, socketOut)
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
          closeUnconditionally(clientIn)
          closeUnconditionally(socketOut)
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
    val pumpSocketStdinToStdout = shell.startThread("bsp-server-to-client", true) {
      var hasReportedServerError: Boolean = false
      while (isConnectionOpen) {
        val socketIn = socket.getInputStream
        try {
          forwardStreamContents(socketIn, clientOut)
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
          closeUnconditionally(socketIn)
          closeUnconditionally(clientOut)
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
        case _: InterruptedException => ()
      }
    }
  }

  def forwardStreamContents(
      in: InputStream,
      out: OutputStream
  ): Unit = {
    val src = Channels.newChannel(in)
    val dest = Channels.newChannel(out)
    try {
      copyContents(src, dest)
    } finally {
      src.close()
      dest.close()
    }
  }

  @volatile var wasSuspended: Boolean = false
  def copyContents(
      src: ReadableByteChannel,
      dest: WritableByteChannel
  ): Unit = {
    val buffer = ByteBuffer.allocateDirect(16 * 1024)

    while (src.read(buffer) != -1) {
      buffer.flip()
      dest.write(buffer)
      buffer.compact()
    }

    buffer.flip()

    while (buffer.hasRemaining()) {
      dest.write(buffer)
    }
  }
}

object BspBridge {

  class LogsRecordingStream(
    maxLines: Int,
    mkString: StringBuilder => String
  ) extends OutputStream {
    val queue =  scala.collection.mutable.Queue.empty[String]
    var currLine = new StringBuilder()
    def write(b: Int): Unit = {
      synchronized {
        if (b == '\n'){
          if (queue.length == maxLines)
            queue.dequeue()
          
          queue.enqueue(mkString(currLine))
          currLine = new StringBuilder()
        } else {
          currLine.append(b.toChar)
        }
      }
    }

    def logs: Seq[String] = {
      val tail = queue.toVector
      if (currLine.nonEmpty)
        tail :+ currLine.toString()
      else
        tail
    }
  }

  object LogsRecordingStream {
    def apply(maxLines: Int): LogsRecordingStream =  {
      val mkString = 
        if (scala.util.Properties.isWin)
          (b: StringBuilder) => { 
              if (b.last == '\r')
                b.deleteCharAt(b.length - 1)
              b.toString
          }
        else
          (b: StringBuilder) => b.toString()
      new LogsRecordingStream(maxLines, mkString)
    }

  }
  

}
