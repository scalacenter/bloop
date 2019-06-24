package bloop.nailgun

import bloop.nailgun.Protocol.ChunkTypes
import bloop.nailgun.ClientAction.Exit
import bloop.nailgun.ClientAction.ExitForcefully
import bloop.nailgun.ClientAction.Print
import bloop.nailgun.ClientAction.SendStdin

import java.net.Socket
import java.io.OutputStream
import java.io.PrintStream
import java.io.InputStream
import java.io.DataOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStreamReader
import java.io.BufferedReader

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.ByteBuffer

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import scala.util.Try
import scala.util.Failure
import scala.util.Success
import scala.util.control.NonFatal

/**
 * A nailgun client establishes a connection to a nailgun server
 *
 */
class Client(
    stdin: InputStream,
    stdout: PrintStream,
    stderr: PrintStream,
    cwd: Path,
    environment: Map[String, String]
) {
  private val absoluteCwd = cwd.toAbsolutePath().toString
  private val exitCode: AtomicInteger = new AtomicInteger(-1)
  private val isRunning: AtomicBoolean = new AtomicBoolean(false)
  private val anyThreadFailed: AtomicBoolean = new AtomicBoolean(false)
  private val sendStdinSemaphore: Semaphore = new Semaphore(0)
  private val waitTermination: Semaphore = new Semaphore(0)

  private val stdinThreadMonitor = new AnyRef
  private val heartbeatThreadMonitor = new AnyRef

  val NailgunFileSeparator = java.io.File.separator
  val NailgunPathSeparator = java.io.File.pathSeparator
  def allEnvironment: Map[String, String] = {
    environment ++ Map(
      "NAILGUN_FILESEPARATOR" -> NailgunFileSeparator,
      "NAILGUN_PATHSEPARATOR" -> NailgunPathSeparator,
      "NAILGUN_TTY_0" -> Integer.toString(Terminal.hasTerminalAttached(0)),
      "NAILGUN_TTY_1" -> Integer.toString(Terminal.hasTerminalAttached(1)),
      "NAILGUN_TTY_2" -> Integer.toString(Terminal.hasTerminalAttached(2))
    )
  }

  def printException(exception: Throwable): Unit = {
    stderr.println("Unexpected error forces client exit!")
    exception.printStackTrace(stderr)
  }

  def sendCommand(
      cmd: String,
      cmdArgs: Array[String],
      out0: OutputStream,
      in0: InputStream
  ): Int = {
    isRunning.set(true)
    val in = new DataInputStream(in0)
    val out = new DataOutputStream(out0)

    val sendStdin = createStdinThread(out)
    val scheduleHeartbeat = createHeartbeatThread(out)
    // Start heartbeat thread before sending command as python and C clients do
    scheduleHeartbeat.start()

    try {
      // Send client command's environment to Nailgun server
      cmdArgs.foreach(sendChunk(ChunkTypes.Argument, _, out))
      allEnvironment.foreach(kv => sendChunk(ChunkTypes.Environment, s"${kv._1}=${kv._2}", out))
      sendChunk(ChunkTypes.Directory, absoluteCwd, out)
      sendChunk(ChunkTypes.Command, cmd, out)

      // Start thread sending stdin right after sending command
      sendStdin.start()

      while (exitCode.get() == -1) {
        val action = processChunkFromServer(in)
        action match {
          case Exit(code) =>
            exitCode.compareAndSet(-1, code)
          case ExitForcefully(error) =>
            exitCode.compareAndSet(-1, 1)
            printException(error)
          case Print(bytes, ps) => ps.write(bytes)
          case SendStdin => sendStdinSemaphore.release()
        }
      }
    } catch {
      case NonFatal(exception) =>
        exitCode.compareAndSet(-1, 1)
        printException(exception)
    } finally {
      // Always disable `isRunning` when client finishes the command execution
      isRunning.compareAndSet(true, false)
      // Release with max to guarantee all `acquire` return
      waitTermination.release(Int.MaxValue)
      // Release stdin semaphore if `acquire` was done by `sendStdin` thread
      if (sendStdinSemaphore.getQueueLength() > 0)
        sendStdinSemaphore.release()
    }

    sendStdin.join()
    scheduleHeartbeat.join()
    exitCode.get()
  }

  def sendChunk(tpe: ChunkTypes.ChunkType, msg: String, out: DataOutputStream): Unit = {
    val payload = msg.getBytes(StandardCharsets.UTF_8)
    out.writeInt(payload.length)
    out.writeByte(tpe.toByteRepr.toInt)
    out.write(payload)
    out.flush()
  }

  def processChunkFromServer(in: DataInputStream): ClientAction = {
    def readPayload(length: Int, in: DataInputStream): Array[Byte] = {
      var total: Int = 0
      val bytes = new Array[Byte](length)
      while (total < length) {
        val read = in.read(bytes, total, length - total)
        if (read < 0) {
          // Error before reaching goal of read bytes
          throw new EOFException("Couldn't read bytes from server")
        } else {
          total += read
        }
      }
      bytes
    }

    val readAction = Try {
      val bytesToRead = in.readInt()
      val chunkType = in.readByte()
      chunkType match {
        case ChunkTypes.SendInput.toByteRepr =>
          ClientAction.SendStdin
        case ChunkTypes.Stdout.toByteRepr =>
          ClientAction.Print(readPayload(bytesToRead, in), stdout)
        case ChunkTypes.Stderr.toByteRepr =>
          ClientAction.Print(readPayload(bytesToRead, in), stderr)
        case ChunkTypes.Exit.toByteRepr =>
          val bytes = readPayload(bytesToRead, in)
          val code = Integer.parseInt(new String(bytes, StandardCharsets.US_ASCII))
          ClientAction.Exit(code)
        case _ =>
          val error = new RuntimeException(s"Unexpected chunk type: $chunkType")
          ClientAction.ExitForcefully(error)
      }
    }

    readAction match {
      case Success(action) => action
      case Failure(exception) => ClientAction.ExitForcefully(exception)
    }
  }

  def createHeartbeatThread(out: DataOutputStream): Thread = daemonThread { () =>
    var continue: Boolean = true
    while (continue) {
      val acquired = waitTermination.tryAcquire(
        Protocol.Time.DefaultHeartbeatIntervalMillis,
        TimeUnit.MILLISECONDS
      )
      if (acquired) {
        continue = false
      } else {
        swallowExceptionsIfServerFinished {
          out.synchronized {
            sendChunk(ChunkTypes.Heartbeat, "", out)
          }
        }
      }
    }
  }

  def createStdinThread(out: DataOutputStream): Thread = daemonThread { () =>
    // Don't start sending input until send stdin is received from server
    sendStdinSemaphore.acquire()

    val reader = new BufferedReader(new InputStreamReader(stdin))
    var continue: Boolean = true
    while (continue) {
      if (!isRunning.get()) {
        continue = false
      } else {
        // TODO: Read available bytes instead of waiting for full line
        val line = reader.readLine()
        if (!isRunning.get()) {
          continue = false
        } else {
          if (line.length() == 0) () // Ignore if read line is empty
          else {
            swallowExceptionsIfServerFinished {
              out.synchronized {
                if (line == null) sendChunk(ChunkTypes.StdinEOF, "", out)
                else sendChunk(ChunkTypes.Stdin, line, out)
              }
            }
          }
        }
      }
    }
  }

  /**
   * Swallows any exception thrown by the closure [[f]] if client exits before
   * the timeout of [[Protocol.Time.SendThreadWaitTerminationMillis]].
   *
   * Ignoring exceptions in this scenario makes sense (exception could have
   * been caught by server finishing connection with client concurrently).
   */
  private def swallowExceptionsIfServerFinished(f: => Unit): Unit = {
    try f
    catch {
      case NonFatal(exception) =>
        // Should always be false while client waits for exit code from server
        val acquired = waitTermination.tryAcquire(
          Protocol.Time.SendThreadWaitTerminationMillis,
          TimeUnit.MILLISECONDS
        )

        // Ignore exception if in less than the wait the client exited
        if (acquired) ()
        else throw exception
    }
  }

  private def daemonThread(run0: () => Unit): Thread = {
    val t = new Thread {
      override def run(): Unit = {
        try run0()
        catch {
          case NonFatal(exception) =>
            if (anyThreadFailed.compareAndSet(false, true)) {
              printException(exception)
            }
        }
      }
    }
    t.setDaemon(true)
    t
  }
}

object Client {
  def main(args: Array[String]): Unit = {
    import scala.collection.JavaConverters._
    val t = Paths.get(
      "/var/folders/1x/1rls38f13h15qb7b9c2cnb740000gp/T/jna--1148075501/jna5158824945545622878.tmp"
    )
    println("HELLO")
    System.load(t.toAbsolutePath().toString)
    /*
    println(Terminal.hasTerminalAttached(0))
    System.load()
    val socket = new Socket(Protocol.Defaults.Host, Protocol.Defaults.Port)
    val code = try {
      val in = socket.getInputStream()
      val out = socket.getOutputStream()
      val cwd = Paths.get(System.getProperty("user.dir"))
      val env = System.getenv().asScala.toMap
      val client = new Client(System.in, System.out, System.err, cwd, env)
      client.sendCommand("about", Array(), out, in)
    } finally {
      socket.shutdownInput()
      socket.shutdownOutput()
      socket.close()
    }

    System.exit(code)
   */
  }
}
