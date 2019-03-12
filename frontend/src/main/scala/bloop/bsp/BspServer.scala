package bloop.bsp

import java.net.ServerSocket
import java.util.Locale

import bloop.cli.Commands
import bloop.data.ClientInfo
import bloop.engine.{ExecutionContext, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, DebugFilter}
import com.martiansoftware.nailgun.{NGUnixDomainServerSocket, NGWin32NamedPipeServerSocket}

import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import monix.reactive.{Observer}

import scala.concurrent.Promise
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer}

object BspServer {
  private implicit val logContext: DebugFilter = DebugFilter.Bsp

  import java.net.InetSocketAddress
  private sealed trait ConnectionHandle { def serverSocket: ServerSocket }
  private case class WindowsLocal(pipeName: String, serverSocket: ServerSocket)
      extends ConnectionHandle
  private case class UnixLocal(path: AbsolutePath, serverSocket: ServerSocket)
      extends ConnectionHandle
  private case class Tcp(address: InetSocketAddress, serverSocket: ServerSocket)
      extends ConnectionHandle

  import Commands.ValidatedBsp
  private def initServer(cmd: ValidatedBsp, state: State): Task[ConnectionHandle] = {
    cmd match {
      case Commands.WindowsLocalBsp(pipeName, _) =>
        val server = new NGWin32NamedPipeServerSocket(pipeName)
        state.logger.debug(s"Waiting for a connection at pipe $pipeName...")
        Task(WindowsLocal(pipeName, server)).doOnCancel(Task(server.close()))
      case Commands.UnixLocalBsp(socketFile, _) =>
        val server = new NGUnixDomainServerSocket(socketFile.toString)
        state.logger.debug(s"Waiting for a connection at $socketFile...")
        Task(UnixLocal(socketFile, server)).doOnCancel(Task(server.close()))
      case Commands.TcpBsp(address, portNumber, _) =>
        val socketAddress = new InetSocketAddress(address, portNumber)
        val server = new java.net.ServerSocket(portNumber, 10, address)
        state.logger.debug(s"Waiting for a connection at $socketAddress...")
        Task(Tcp(socketAddress, server)).doOnCancel(Task(server.close()))
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
      promiseWhenStarted: Option[Promise[Unit]],
      observer: Option[Observer.Sync[State]],
      scheduler: Scheduler
  ): Task[State] = {
    def uri(handle: ConnectionHandle): String = {
      handle match {
        case w: WindowsLocal => s"local:${w.pipeName}"
        case u: UnixLocal => s"local://${u.path.syntax}"
        case t: Tcp => s"tcp://${t.address.getHostString}:${t.address.getPort}"
      }
    }

    import state.logger
    def startServer(handle: ConnectionHandle): Task[State] = {
      val connectionURI = uri(handle)
      // WARNING: Do NOT change this log, it's used by clients and bsp launcher to know when to start a connection
      logger.info(s"The server is listening for incoming connections at $connectionURI...")
      promiseWhenStarted.foreach(_.success(()))
      val socket = handle.serverSocket.accept()
      logger.info(s"Accepted incoming BSP client connection at $connectionURI")

      val exitStatus = Atomic(0)
      val in = socket.getInputStream
      val out = socket.getOutputStream
      val bspLogger = new BspClientLogger(logger)
      val client = new LanguageClient(out, bspLogger)
      val servicesProvider =
        new BloopBspServices(state, client, configPath, in, exitStatus, observer)
      val bloopServices = servicesProvider.services
      val messages = BaseProtocolMessage.fromInputStream(in, bspLogger)
      val server = new LanguageServer(messages, client, bloopServices, scheduler, bspLogger)

      def error(msg: String): Unit = servicesProvider.stateAfterExecution.logger.error(msg)
      server.startTask
        .doOnCancel {
          Task {
            error(s"BSP server cancelled, closing socket...")
            try closeSocket(cmd, socket)
            finally handle.serverSocket.close()
          }
        }
        .doOnFinish { result =>
          Task {
            result.foreach(t => error(s"BSP server stopped by ${t.getMessage}"))
            try closeSocket(cmd, socket)
            finally handle.serverSocket.close()
          }
        }
        .map(_ => servicesProvider.stateAfterExecution)
    }

    initServer(cmd, state).materialize.flatMap {
      case scala.util.Success(handle: ConnectionHandle) =>
        startServer(handle).onErrorRecoverWith {
          case t: Throwable =>
            Task.now(state.withError(s"BSP server failed to start with ${t.getMessage}", t))
        }
      case scala.util.Failure(t: Throwable) =>
        promiseWhenStarted.foreach { p =>
          if (!p.isCompleted) p.failure(t)
        }

        Task.now(state.withError(s"BSP server failed to open a socket: '${t.getMessage}'", t))
    }
  }
}
