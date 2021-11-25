package bloop.bloopgun.util

import snailgun.logging.Logger
import snailgun.logging.SnailgunLogger
import snailgun.protocol.Defaults
import snailgun.protocol.Protocol
import snailgun.protocol.Streams

import java.net.Socket
import java.nio.file.Paths
import java.nio.file.Path
import java.io.PrintStream
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.net.SocketException
import snailgun.Client
import libdaemonjvm.SocketPaths
import libdaemonjvm.client.Connect
import libdaemonjvm.internal.SocketHandler
import java.io.IOException
import java.nio.channels.Channels
import java.io.OutputStream
import java.nio.channels.ReadableByteChannel
import java.nio.ByteBuffer

final case class DomainSocketClient(socketPaths: SocketPaths) extends Client {
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
    val socket = SocketHandler.client(socketPaths) match {
      case Left(socket0) => socket0
      case Right(channel) => libdaemonjvm.Util.socketFromChannel(channel)
    }
    try {
      val in = socket.getInputStream()
      val out = socket.getOutputStream()
      val protocol = new Protocol(streams, cwd, env, logger, stop, interactiveSession)
      protocol.sendCommand(cmd, args, out, in)
    } finally {
      try {
        if (socket.isClosed()) ()
        else {
          try socket.shutdownInput()
          finally {
            try socket.shutdownOutput()
            finally socket.close()
          }
        }
      } catch {
        case t: IOException =>
          logger.debug("Tracing an ignored socket exception...")
          // logger.trace(t)
          ()
        case t: SocketException =>
          logger.debug("Tracing an ignored socket exception...")
          logger.trace(t)
          ()
      }
    }
  }
}
