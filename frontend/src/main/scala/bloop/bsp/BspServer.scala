package bloop.bsp

import java.net.ServerSocket
import java.util.Locale

import bloop.cli.{Commands, ExitStatus}
import bloop.engine.State
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.Slf4jAdapter
import com.martiansoftware.nailgun.{NGUnixDomainServerSocket, NGWin32NamedPipeServerSocket}
import monix.execution.Scheduler
import monix.execution.atomic.Atomic

import scala.meta.jsonrpc.BaseProtocolMessage
import scala.meta.lsp.{LanguageClient, LanguageServer}

object BspServer {
  private[bloop] val isWindows: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")
  private[bloop] val isMac: Boolean =
    System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("mac")

  import java.net.InetSocketAddress
  private sealed trait ConnectionHandle { def serverSocket: ServerSocket }
  private case class WindowsLocal(pipeName: String, serverSocket: ServerSocket)
      extends ConnectionHandle
  private case class UnixLocal(path: AbsolutePath, serverSocket: ServerSocket)
      extends ConnectionHandle
  private case class Tcp(address: InetSocketAddress, serverSocket: ServerSocket)
      extends ConnectionHandle

  import Commands.ValidatedBsp
  import monix.{eval => me}
  private def initServer(cmd: ValidatedBsp, state: State): me.Task[ConnectionHandle] = {
    cmd match {
      case Commands.WindowsLocalBsp(pipeName, _) =>
        val server = new NGWin32NamedPipeServerSocket(pipeName)
        state.logger.debug(s"Waiting for a connection at pipe $pipeName...")
        me.Task(WindowsLocal(pipeName, server)).doOnCancel(me.Task(server.close()))
      case Commands.UnixLocalBsp(socketFile, _) =>
        val server = new NGUnixDomainServerSocket(socketFile.toString)
        state.logger.debug(s"Waiting for a connection at $socketFile...")
        me.Task(UnixLocal(socketFile, server)).doOnCancel(me.Task(server.close()))
      case Commands.TcpBsp(address, portNumber, _) =>
        val socketAddress = new InetSocketAddress(address, portNumber)
        val server = new java.net.ServerSocket(portNumber, 10, address)
        state.logger.debug(s"Waiting for a connection at $socketAddress...")
        me.Task(Tcp(socketAddress, server)).doOnCancel(me.Task(server.close()))
    }
  }

  private[bloop] def closeSocket(cmd: Commands.ValidatedBsp, socket: java.net.Socket): Unit = {
    cmd match {
      case _: Commands.TcpBsp if !socket.isClosed() => socket.close()
      case _: Commands.WindowsLocalBsp if !socket.isClosed() => socket.close()
      case _: Commands.UnixLocalBsp if !socket.isClosed() =>
        if (!socket.isInputShutdown) socket.shutdownInput()
        if (!socket.isOutputShutdown) socket.shutdownOutput()
        socket.close()
      case _ => ()
    }
  }

  def run(
      cmd: ValidatedBsp,
      state: State,
      configPath: RelativePath,
      scheduler: Scheduler
  ): me.Task[State] = {
    def uri(handle: ConnectionHandle): String = {
      handle match {
        case w: WindowsLocal => s"local:${w.pipeName}"
        case u: UnixLocal => s"local://${u.path.syntax}"
        case t: Tcp => s"tcp://${t.address.getHostString}:${t.address.getPort}"
      }
    }

    import state.logger
    def startServer(handle: ConnectionHandle): me.Task[State] = {
      val connectionURI = uri(handle)
      logger.debug(s"The server is listening for incoming connections at $connectionURI...")
      val socket = handle.serverSocket.accept()
      logger.info(s"Accepted incoming BSP client connection at $connectionURI")

      val exitStatus = Atomic(0)
      val in = socket.getInputStream
      val out = socket.getOutputStream
      val bspLogger = com.typesafe.scalalogging.Logger(new Slf4jAdapter(logger))
      val client = new LanguageClient(out, bspLogger)
      val servicesProvider = new BloopBspServices(state, client, configPath, in, exitStatus)
      val bloopServices = servicesProvider.services
      val messages = BaseProtocolMessage.fromInputStream(in)
      val server = new LanguageServer(messages, client, bloopServices, scheduler, bspLogger)

      server.startTask
        .map(_ => servicesProvider.latestState)
        .onErrorHandleWith(t =>
          servicesProvider.latestState.withError(s"BSP server was stopped by ${t.getMessage}"))
        .doOnFinish(_ => me.Task { handle.serverSocket.close() })
    }

    initServer(cmd, state).materialize.flatMap {
      case scala.util.Success(handle: ConnectionHandle) =>
        startServer(handle).onErrorRecoverWith {
          case t: Throwable =>
            state.withError(s"BSP server failed to start with ${t.getMessage}", t)
        }
      case scala.util.Failure(t: Throwable) =>
        state.withError(s"BSP server failed to open a socket: '${t.getMessage}'", t)
    }
  }
}
