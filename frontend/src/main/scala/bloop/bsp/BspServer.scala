package bloop.bsp

import java.net.ServerSocket
import java.util.Locale
import java.nio.file.{Files, Path}

import bloop.cli.{BspProtocol, Commands}
import bloop.engine.{ExecutionContext, State}
import com.martiansoftware.nailgun.{NGUnixDomainServerSocket, NGWin32NamedPipeServerSocket}
import monix.execution.{Cancelable, Scheduler}

object BspServer {
  private[bloop] val isWindows: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")
  private[bloop] val isMac: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("mac")

  import java.net.InetSocketAddress
  private sealed trait ConnectionHandle { def serverSocket: ServerSocket }
  private case class WindowsLocal(pipeName: String, serverSocket: ServerSocket) extends ConnectionHandle
  private case class UnixLocal(path: Path, serverSocket: ServerSocket) extends ConnectionHandle
  private case class Tcp(address: InetSocketAddress, serverSocket: ServerSocket) extends ConnectionHandle

  import Commands.ValidatedBsp
  import monix.{eval => me}
  private def initServer(cmd: ValidatedBsp, state: State): me.Task[ConnectionHandle] = {
    cmd match {
      case Commands.WindowsLocalBsp(pipeName, _) =>
        state.logger.debug(s"Establishing server connection at pipe $pipeName")
        val server = new NGWin32NamedPipeServerSocket(pipeName)
        me.Task(WindowsLocal(pipeName, server)).doOnCancel(me.Task(server.close()))
      case Commands.UnixLocalBsp(socketFile, _) =>
        state.logger.debug(s"Establishing server connection at $socketFile")
        val server = new NGUnixDomainServerSocket(socketFile.toString)
        me.Task(UnixLocal(socketFile, server)).doOnCancel(me.Task(server.close()))
      case Commands.TcpBsp(address, portNumber, name) =>
        val socketAddress = new InetSocketAddress(address, portNumber)
        state.logger.debug(s"Establishing server connection via TCP at ${socketAddress}")
        val server = new java.net.ServerSocket(portNumber, 10, address)
        me.Task(Tcp(socketAddress, server)).doOnCancel(me.Task(server.close()))
    }
  }

  private[bloop] def closeSocket(cmd: Commands.ValidatedBsp, socket: java.net.Socket): Unit = {
    println("Closing socket")
    cmd match {
      case _: Commands.TcpBsp if !socket.isClosed() =>
        socket.close()
      case _: Commands.WindowsLocalBsp if !socket.isClosed() => socket.close()
      case _: Commands.UnixLocalBsp if !socket.isClosed() =>
        if (!socket.isInputShutdown) socket.shutdownInput()
        if (!socket.isOutputShutdown) socket.shutdownOutput()
        socket.close()
      case _ => ()
    }
  }

  private final val bspLogger = com.typesafe.scalalogging.Logger(this.getClass)
  def run(cmd: ValidatedBsp, state: State, scheduler: Scheduler): me.Task[State] = {
    import org.langmeta.lsp.LanguageClient
    import org.langmeta.lsp.LanguageServer
    import org.langmeta.jsonrpc.BaseProtocolMessage

    def uri(handle: ConnectionHandle): String = {
      handle match {
        case w: WindowsLocal => s"local:${w.pipeName}"
        case u: UnixLocal => s"local://${u.path.toString}"
        case t: Tcp => s"tcp://${t.address.getHostString}:${t.address.getPort}"
      }
    }

    import state.logger
    def startServer(handle: ConnectionHandle): me.Task[State] = {
      val connectionURI = uri(handle)
      logger.debug(s"The server is starting to listen at ${connectionURI}")
      val socket = handle.serverSocket.accept()
      logger.info(s"Accepted incoming BSP client connection at ${connectionURI}.")

      val in = socket.getInputStream
      val out = socket.getOutputStream
      val client = new LanguageClient(out, bspLogger)
      val servicesProvider = new BloopBspServices(state, client, bspLogger)
      val bloopServices = servicesProvider.services
      val messages = BaseProtocolMessage.fromInputStream(in)
      val server = new LanguageServer(messages, client, bloopServices, scheduler, bspLogger)

      server.startTask
        .map(_ => servicesProvider.latestState)
        .doOnCancel(me.Task {
          //closeSocket(cmd, socket)
          handle.serverSocket.close()
        })
    }

    initServer(cmd, state)
      .flatMap(handle => startServer(handle))
      .executeWithOptions(_.enableAutoCancelableRunLoops)
/*
    initServer(cmd, state).materialize.flatMap {
      case scala.util.Success(handle: ConnectionHandle) =>
        startServer(handle).onErrorRecover {
          case t: Throwable =>
            logger.error(t.getMessage)
            logger.trace(t)
            state
        }
      case scala.util.Failure(t: Throwable) =>
        me.Task {
          logger.error(s"The bsp server could not open a socket because of '${t.getMessage}'.")
          logger.trace(t)
          state
        }
    }*/
  }
}
