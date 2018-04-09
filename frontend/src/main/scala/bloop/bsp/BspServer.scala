package bloop.bsp

import java.net.ServerSocket
import java.util.Locale
import java.nio.file.{Files, Path}

import bloop.cli.{BspProtocol, Commands}
import bloop.engine.{ExecutionContext, State}
import bloop.io.{AbsolutePath, RelativePath}
import com.martiansoftware.nailgun.{NGUnixDomainServerSocket, NGWin32NamedPipeServerSocket}
import monix.execution.{Cancelable, Scheduler}

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
        state.logger.debug(s"Waiting for a connection at pipe $pipeName")
        me.Task(WindowsLocal(pipeName, server)).doOnCancel(me.Task(server.close()))
      case Commands.UnixLocalBsp(socketFile, _) =>
        val server = new NGUnixDomainServerSocket(socketFile.toString)
        state.logger.debug(s"Waiting for a connection at $socketFile")
        me.Task(UnixLocal(socketFile, server)).doOnCancel(me.Task(server.close()))
      case Commands.TcpBsp(address, portNumber, name) =>
        val socketAddress = new InetSocketAddress(address, portNumber)
        val server = new java.net.ServerSocket(portNumber, 10, address)
        state.logger.debug(s"Waiting for a connection at $socketAddress")
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

  private final val bspLogger = com.typesafe.scalalogging.Logger(this.getClass)
  def run(
      cmd: ValidatedBsp,
      state: State,
      configPath: RelativePath,
      scheduler: Scheduler
  ): me.Task[State] = {
    import org.langmeta.lsp.LanguageClient
    import org.langmeta.lsp.LanguageServer
    import org.langmeta.jsonrpc.BaseProtocolMessage

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
      logger.debug(s"The server is starting to listen at ${connectionURI}")
      val socket = handle.serverSocket.accept()
      logger.info(s"Accepted incoming BSP client connection at ${connectionURI}.")

      val in = socket.getInputStream
      val out = socket.getOutputStream
      val client = new LanguageClient(out, bspLogger)
      val servicesProvider = new BloopBspServices(state, client, configPath, bspLogger)
      val bloopServices = servicesProvider.services
      val messages = BaseProtocolMessage.fromInputStream(in)
      val server = new LanguageServer(messages, client, bloopServices, scheduler, bspLogger)

      server.startTask
        .map(_ => servicesProvider.latestState)
        .doOnFinish(_ => me.Task { handle.serverSocket.close() })
    }

    val runTask = initServer(cmd, state).materialize.flatMap {
      case scala.util.Success(handle: ConnectionHandle) =>
        startServer(handle).onErrorRecover {
          case t: Throwable =>
            logger.error(t.getMessage)
            logger.trace(t)
            state
        }
      case scala.util.Failure(t: Throwable) =>
        me.Task {
          logger.error(s"BSP server failed to open a socket: '${t.getMessage}'")
          logger.trace(t)
          state
        }
    }

    runTask.executeWithOptions(_.enableAutoCancelableRunLoops)
  }
}
