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
    var heartbeatThread: Thread = null

    try {
      logger.debug(s"Sending arguments '${cmdArgs.mkString(" ")}' to Nailgun server")
      cmdArgs.foreach(sendChunk(ChunkTypes.Argument, _, out))
      logger.debug("Sending environment variables to Nailgun server")
      allEnvironment.foreach(kv => sendChunk(ChunkTypes.Environment, s"${kv._1}=${kv._2}", out))
      logger.debug(s"Sending working directory $absoluteCwd to Nailgun server")
      sendChunk(ChunkTypes.Directory, absoluteCwd, out)
      logger.debug(s"Sending command $cmd to Nailgun server")
      sendChunk(ChunkTypes.Command, cmd, out)

      // Start heartbeat thread AFTER command is sent
      logger.debug("Starting heartbeat thread...")
      heartbeatThread = createHeartbeatThread(in, out)

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
    if (heartbeatThread != null) {
      logger.debug("Waiting for heartbeat thread to finish...")
      heartbeatThread.join()
    }
    logger.debug("Returning exit code...")
    exitCode.get()
  }

  private def createHeartbeatThread(in: DataInputStream, out: DataOutputStream): Thread =
    daemonThread("bloop-nailgun-heartbeat")(() => heartbeatLoop(in, out))

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
      val chunkType = in.readByte()
      val chunkTypeChar = if (chunkType >= 32 && chunkType < 127) chunkType.toChar.toString else "?"
      logger.debug(s"Received chunk: type=$chunkTypeChar ($chunkType), length=$bytesToRead")
      chunkType match {
        case ChunkTypes.SendInput => 
          logger.debug("  -> SendInput")
          Action.SendStdin
        case ChunkTypes.Stdout => 
          val payload = readPayload(bytesToRead)
          logger.debug(s"  -> Stdout: ${new String(payload, StandardCharsets.UTF_8).take(100)}")
          Action.Print(payload, streams.out)
        case ChunkTypes.Stderr => 
          val payload = readPayload(bytesToRead)
          logger.debug(s"  -> Stderr: ${new String(payload, StandardCharsets.UTF_8).take(100)}")
          Action.Print(payload, streams.err)
        case ChunkTypes.Exit =>
          val payload = readPayload(bytesToRead)
          val raw = new String(payload, StandardCharsets.US_ASCII).trim
          logger.debug(s"  -> Exit: '$raw' (bytes: ${payload.toList})")
          Action.Exit(Integer.parseInt(raw))
        case other =>
          val payload = if (bytesToRead > 0 && bytesToRead < 1000) readPayload(bytesToRead) else Array.empty[Byte]
          logger.debug(s"  -> Unknown chunk type $other, payload: ${payload.toList}")
          Action.ExitForcefully(new RuntimeException(s"Unexpected chunk type: $other ($chunkTypeChar)"))
      }
    }

    readAction match {
      case Success(action) => action
      case Failure(exception) => 
        logger.debug(s"  -> Failed to read chunk: ${exception.getMessage}")
        Action.ExitForcefully(exception)
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
        def shouldStop = !isRunning.get() || stopFurtherProcessing.get()
        // Defer reader creation until we actually need it (when server requests stdin).
        // This avoids potential issues with System.in in non-interactive environments.
        var readerOpt: Option[BufferedReader] = None
        def getReader(): BufferedReader = readerOpt.getOrElse {
          val r = new BufferedReader(new InputStreamReader(in))
          readerOpt = Some(r)
          r
        }
        try {
          var continue = true
          while (continue) {
            if (shouldStop) continue = false
            else {
              // Don't send input until the server requests it (SendStdin).
              // This blocks until released or interrupted.
              val acquired = sendStdinSemaphore.tryAcquire(100, TimeUnit.MILLISECONDS)
              if (!acquired) {
                // Check if we should stop while waiting
                if (shouldStop) continue = false
                // Otherwise keep waiting
              } else {
                if (shouldStop) continue = false
                else {
                  val reader = getReader()
                  val line = reader.readLine()
                  if (line == null) {
                    // EOF: notify the server and stop.
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
            }
          }
        } finally readerOpt.foreach(_.close())
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
    logger.error("Unexpected error forces client exit.", exception)

  private def daemonThread(name: String)(run0: () => Unit): Thread = {
    val runnable: Runnable = new Runnable {
      def run(): Unit = {
        try run0()
        catch {
          case NonFatal(exception) =>
            if (anyThreadFailed.compareAndSet(false, true)) printException(exception)
          case fatal: Throwable =>
            if (anyThreadFailed.compareAndSet(false, true)) printException(fatal)
            throw fatal
        }
      }
    }
    val handler: Thread.UncaughtExceptionHandler = new Thread.UncaughtExceptionHandler {
      def uncaughtException(thread: Thread, ex: Throwable): Unit =
        logger.error(s"Uncaught exception in thread ${thread.getName}", ex)
    }
    val t = new Thread(runnable, name)
    t.setDaemon(true)
    t.setUncaughtExceptionHandler(handler)
    t.start()
    t
  }
}
