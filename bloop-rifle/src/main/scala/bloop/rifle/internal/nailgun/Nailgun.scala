package bloop.rifle.internal.nailgun

import bloop.rifle.BloopRifleLogger

import java.io.BufferedReader
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NonFatal

/**
 * A minimal client of the Nailgun protocol
 * (http://www.martiansoftware.com/nailgun/protocol.html).
 *
 * Adapted from the (unmaintained, last released 2023) scala-cli fork of snailgun
 * (https://github.com/scala-cli/snailgun, Apache-2.0), trimmed to what bloop needs:
 * only the daemon-thread execution path, `BloopRifleLogger` for tracing, and a stdin
 * forwarder that preserves the line terminator and handles EOF (so applications run
 * via `bloop run` can read stdin with readInt/readLine/Scanner).
 */
final case class Streams(
    in: Option[InputStream],
    out: OutputStream,
    err: OutputStream,
    inIsATty: Int = 0,
    outIsATty: Int = 0,
    errIsATty: Int = 0
)

private[nailgun] object ChunkTypes {
  // One-byte chunk-type tags, as defined by the Nailgun protocol.
  final val Stdin: Byte = '0'
  final val Stdout: Byte = '1'
  final val Stderr: Byte = '2'
  final val StdinEOF: Byte = '.'
  final val SendInput: Byte = 'S'
  final val Heartbeat: Byte = 'H'
  final val Environment: Byte = 'E'
  final val Directory: Byte = 'D'
  final val Command: Byte = 'C'
  final val Argument: Byte = 'A'
  final val Exit: Byte = 'X'
}

private[nailgun] sealed trait Action
private[nailgun] object Action {
  case object SendStdin extends Action
  final case class Exit(code: Int) extends Action
  final case class ExitForcefully(error: Throwable) extends Action
  final case class Print(bytes: Array[Byte], out: OutputStream) extends Action {
    override def toString: String = s"Print(${bytes.length} bytes)"
  }
}

/** A Nailgun client over a concrete transport (TCP socket or domain socket). */
abstract class Client {
  def run(
      cmd: String,
      args: Array[String],
      cwd: Path,
      env: Map[String, String],
      streams: Streams,
      logger: BloopRifleLogger,
      stop: AtomicBoolean,
      interactiveSession: Boolean
  ): Int
}

/** Connects to a Nailgun server over TCP. */
final class TcpClient(addr: InetAddress, port: Int) extends Client {
  def run(
      cmd: String,
      args: Array[String],
      cwd: Path,
      env: Map[String, String],
      streams: Streams,
      logger: BloopRifleLogger,
      stop: AtomicBoolean,
      interactiveSession: Boolean
  ): Int = {
    val socket = new Socket(addr, port)
    try {
      val protocol = new Protocol(streams, cwd, env, logger, stop, interactiveSession)
      protocol.sendCommand(cmd, args, socket.getOutputStream(), socket.getInputStream())
    } finally
      try
        if (socket.isClosed()) ()
        else
          try socket.shutdownInput()
          finally
            try socket.shutdownOutput()
            finally socket.close()
      catch {
        case t: SocketException =>
          logger.debug("Ignoring socket exception while closing the connection", t)
      }
  }
}

object TcpClient {
  def apply(host: String, port: Int): TcpClient =
    new TcpClient(InetAddress.getByName(host), port)
}

/** Connects to a Nailgun server over a socket opened on demand (e.g. a Unix domain socket). */
final class SocketClient(openSocket: () => Socket) extends Client {
  def run(
      cmd: String,
      args: Array[String],
      cwd: Path,
      env: Map[String, String],
      streams: Streams,
      logger: BloopRifleLogger,
      stop: AtomicBoolean,
      interactiveSession: Boolean
  ): Int = {
    var socket: Socket = null
    try {
      socket = openSocket()
      val protocol = new Protocol(streams, cwd, env, logger, stop, interactiveSession)
      protocol.sendCommand(cmd, args, socket.getOutputStream(), socket.getInputStream())
    } finally
      try
        if (socket != null && !socket.isClosed())
          try socket.shutdownInput()
          finally
            try socket.shutdownOutput()
            finally socket.close()
      catch {
        case t: SocketException =>
          logger.debug("Ignoring socket exception while closing the connection", t)
      }
  }
}

object SocketClient {
  def apply(openSocket: () => Socket): SocketClient = new SocketClient(openSocket)
}

private[nailgun] object Protocol {
  val HeartbeatIntervalMillis: Long = 500L
  val SendThreadWaitTerminationMillis: Long = 5000L
}

/**
 * The Nailgun protocol state machine for a single command invocation.
 *
 * Sends the command (arguments, environment, working directory) to the server, then
 * loops over server chunks forwarding stdout/stderr, replying to heartbeats, and
 * streaming stdin on demand until the server reports an exit code.
 */
final class Protocol(
    streams: Streams,
    cwd: Path,
    environment: Map[String, String],
    logger: BloopRifleLogger,
    stopFurtherProcessing: AtomicBoolean,
    interactiveSession: Boolean
) {
  import Protocol._

  private val absoluteCwd = cwd.toAbsolutePath().toString
  private val exitCode: AtomicInteger = new AtomicInteger(-1)
  private val isRunning: AtomicBoolean = new AtomicBoolean(false)
  private val anyThreadFailed: AtomicBoolean = new AtomicBoolean(false)
  private val waitTermination: Semaphore = new Semaphore(0)

  private def allEnvironment: Map[String, String] =
    environment ++ Seq(
      "NAILGUN_FILESEPARATOR" -> java.io.File.separator,
      "NAILGUN_PATHSEPARATOR" -> java.io.File.pathSeparator,
      "NAILGUN_TTY_0" -> streams.inIsATty.toString,
      "NAILGUN_TTY_1" -> streams.outIsATty.toString,
      "NAILGUN_TTY_2" -> streams.errIsATty.toString
    )

  def sendCommand(
      cmd: String,
      cmdArgs: Array[String],
      out0: OutputStream,
      in0: InputStream
  ): Int = {
    isRunning.set(true)
    val in = new DataInputStream(in0)
    val out = new DataOutputStream(out0)

    var sendStdinOpt = Option.empty[(Thread, Semaphore)]
    val heartbeatThread = daemonThread("bloop-nailgun-heartbeat")(() => heartbeatLoop(in, out))

    try {
      logger.debug(s"Sending arguments '${cmdArgs.mkString(" ")}' to Nailgun server")
      cmdArgs.foreach(sendChunk(ChunkTypes.Argument, _, out))
      logger.debug("Sending environment variables to Nailgun server")
      allEnvironment.foreach(kv => sendChunk(ChunkTypes.Environment, s"${kv._1}=${kv._2}", out))
      logger.debug(s"Sending working directory $absoluteCwd to Nailgun server")
      sendChunk(ChunkTypes.Directory, absoluteCwd, out)
      logger.debug(s"Sending command $cmd to Nailgun server")
      sendChunk(ChunkTypes.Command, cmd, out)

      logger.debug("Starting thread to read stdin...")
      sendStdinOpt = createStdinThread(out)

      while (exitCode.get() == -1) {
        val action = processChunkFromServer(in)
        action match {
          case Action.Exit(code) =>
            exitCode.compareAndSet(-1, code)
          case Action.ExitForcefully(error) =>
            if (cmd == "ng-stop") exitCode.compareAndSet(-1, 0)
            else {
              exitCode.compareAndSet(-1, 1)
              printException(error)
            }
          case Action.Print(bytes, target) => target.write(bytes); target.flush()
          case Action.SendStdin => sendStdinOpt.foreach(_._2.release())
        }
      }
    } catch {
      case NonFatal(exception) =>
        exitCode.compareAndSet(-1, 1)
        if (!stopFurtherProcessing.get()) printException(exception)
    } finally {
      isRunning.compareAndSet(true, false)
      waitTermination.release(Int.MaxValue)
      sendStdinOpt.foreach(_._2.release(Int.MaxValue))
    }

    // Don't join the stdin reader: it's a daemon thread that may be parked in a blocking read
    // on the client's real stdin (the server requests input eagerly, even for programs that
    // never consume it). Joining would force the user to press Enter before the command can
    // return. Interrupt as best-effort (unblocks the semaphore-parked case) and abandon it —
    // it dies with the client process.
    sendStdinOpt.foreach(_._1.interrupt())
    logger.debug("Waiting for heartbeat thread to finish...")
    heartbeatThread.join()
    logger.debug("Returning exit code...")
    exitCode.get()
  }

  private def sendChunk(tpe: Byte, msg: String, out: DataOutputStream): Unit = {
    val payload = msg.getBytes(StandardCharsets.UTF_8)
    out.writeInt(payload.length)
    out.writeByte(tpe.toInt)
    out.write(payload)
    out.flush()
  }

  private def processChunkFromServer(in: DataInputStream): Action = {
    def readPayload(length: Int): Array[Byte] = {
      var total = 0
      val bytes = new Array[Byte](length)
      while (total < length) {
        val read = in.read(bytes, total, length - total)
        if (read < 0) throw new EOFException("Couldn't read bytes from server")
        else total += read
      }
      bytes
    }

    val readAction = Try {
      val bytesToRead = in.readInt()
      in.readByte() match {
        case ChunkTypes.SendInput => Action.SendStdin
        case ChunkTypes.Stdout => Action.Print(readPayload(bytesToRead), streams.out)
        case ChunkTypes.Stderr => Action.Print(readPayload(bytesToRead), streams.err)
        case ChunkTypes.Exit =>
          val raw = new String(readPayload(bytesToRead), StandardCharsets.US_ASCII).trim
          Action.Exit(Integer.parseInt(raw))
        case other =>
          Action.ExitForcefully(new RuntimeException(s"Unexpected chunk type: $other"))
      }
    }

    readAction match {
      case Success(action) => action
      case Failure(exception) => Action.ExitForcefully(exception)
    }
  }

  private def heartbeatLoop(in: DataInputStream, out: DataOutputStream): Unit = {
    var continue = true
    while (continue) {
      val done = waitTermination.tryAcquire(HeartbeatIntervalMillis, TimeUnit.MILLISECONDS)
      if (done) continue = false
      else
        swallowExceptionsIfServerFinished {
          if (stopFurtherProcessing.get())
            out.synchronized {
              out.flush()
              try in.close()
              finally out.close()
            }
          out.synchronized(sendChunk(ChunkTypes.Heartbeat, "", out))
        }
    }
  }

  private def createStdinThread(out: DataOutputStream): Option[(Thread, Semaphore)] =
    streams.in.map { in =>
      val sendStdinSemaphore = new Semaphore(0)
      val thread = daemonThread("bloop-nailgun-stdin") { () =>
        val reader = new BufferedReader(new InputStreamReader(in))
        def shouldStop = !isRunning.get() || stopFurtherProcessing.get()
        try {
          var continue = true
          while (continue) {
            if (shouldStop) continue = false
            else {
              // Don't send input until the server requests it (SendStdin).
              sendStdinSemaphore.acquire()
              val line = if (shouldStop) null else reader.readLine()
              if (shouldStop) continue = false
              else if (line == null) {
                // EOF: notify the server and stop. Checked before `line.length`, which
                // would NPE on a null line.
                swallowExceptionsIfServerFinished {
                  out.synchronized(sendChunk(ChunkTypes.StdinEOF, "", out))
                }
                continue = false
              } else
                // Forward the line *with* its terminator. `readLine` strips the newline;
                // without it the forked app's readInt/readLine/Scanner never sees a
                // delimiter and blocks forever.
                swallowExceptionsIfServerFinished {
                  out.synchronized(sendChunk(ChunkTypes.Stdin, line + "\n", out))
                }
            }
          }
        } catch {
          case exception: InterruptedException =>
            logger.debug("Stdin thread interrupted", exception)
        } finally reader.close()
      }
      (thread, sendStdinSemaphore)
    }

  /** Swallow exceptions thrown after the client has already finished the command. */
  private def swallowExceptionsIfServerFinished(f: => Unit): Unit =
    try f
    catch {
      case NonFatal(exception) =>
        val finished =
          waitTermination.tryAcquire(SendThreadWaitTerminationMillis, TimeUnit.MILLISECONDS)
        if (finished) () else throw exception
    }

  private def printException(exception: Throwable): Unit =
    logger.debug("Unexpected error forces client exit!", exception)

  private def daemonThread(name: String)(run0: () => Unit): Thread = {
    val runnable: Runnable = { () =>
      try run0()
      catch {
        case NonFatal(exception) =>
          if (anyThreadFailed.compareAndSet(false, true)) printException(exception)
      }
    }
    val t = new Thread(runnable, name)
    t.setDaemon(true)
    t.start()
    t
  }
}
