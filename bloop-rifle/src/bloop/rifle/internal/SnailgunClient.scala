package bloop.rifle.internal

import libdaemonjvm.errors.SocketExceptionLike
import snailgun.logging.Logger
import snailgun.protocol.{Protocol, Streams}

import java.net.{Socket, SocketException}
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

class SnailgunClient(openSocket: () => Socket) extends snailgun.Client {

  def run(
    cmd: String,
    args: Array[String],
    cwd: Path,
    env: Map[String, String],
    streams: Streams,
    logger: Logger,
    stop: AtomicBoolean,
    interactiveSession: Boolean
  ): Int = {
    var socket: Socket = null
    try {
      socket = openSocket()
      val in       = socket.getInputStream()
      val out      = socket.getOutputStream()
      val protocol = new Protocol(streams, cwd, env, logger, stop, interactiveSession)
      protocol.sendCommand(cmd, args, out, in)
    }
    finally
      try if (socket != null)
          if (socket.isClosed()) ()
          else
            try socket.shutdownInput()
            finally
              try socket.shutdownOutput()
              finally socket.close()
      catch {
        case t: SocketException =>
          logger.debug("Tracing an ignored socket exception...")
          logger.trace(t)
          ()
        case t: SocketExceptionLike =>
          logger.debug("Tracing an ignored socket exception-like...")
          logger.trace(t)
          ()
      }
  }
}

object SnailgunClient {
  def apply(openSocket: () => Socket): SnailgunClient =
    new SnailgunClient(openSocket)
}
