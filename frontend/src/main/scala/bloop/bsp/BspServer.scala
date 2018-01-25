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

  import java.net.{InetAddress, InetSocketAddress}
  private sealed trait ConnectionHandle { def socket: ServerSocket }
  private case class WindowsLocal(pipeName: String, socket: ServerSocket) extends ConnectionHandle
  private case class UnixLocal(path: Path, socket: ServerSocket) extends ConnectionHandle
  private case class Tcp(address: InetSocketAddress, socket: ServerSocket) extends ConnectionHandle

  import monix.{eval => me}
  private def initServer(cmd: Commands.Bsp, state: State): me.Task[ConnectionHandle] = me.Task {
    cmd.protocol match {
      case BspProtocol.Local if isWindows =>
        val pipeName = cmd.pipeName.getOrElse(sys.error("There is no pipe name!"))
        state.logger.debug(s"Establishing server connection at pipe $pipeName")
        WindowsLocal(pipeName, new NGWin32NamedPipeServerSocket(cmd.pipeName.get))
      case BspProtocol.Local =>
        val socketFile = cmd.socket.getOrElse(sys.error(""))
        state.logger.debug(s"Establishing server connection at $socketFile")
        UnixLocal(socketFile, new NGUnixDomainServerSocket(socketFile.toString))
      case BspProtocol.Tcp =>
        val onlyHost = InetAddress.getByName(cmd.host)
        val socketAddress = new InetSocketAddress(onlyHost, cmd.port)
        state.logger.debug(s"Establishing server connection via TCP at ${socketAddress}")
        // Use 0 instead of a concrete number to have an infinite timeout
        Tcp(socketAddress, new java.net.ServerSocket(cmd.port, 0, onlyHost))
    }
  }

  import java.net.Socket
  private case class Established(s: Socket, h: ConnectionHandle)
  private def establishConnection(server: me.Task[ConnectionHandle]): me.Task[Established] = {
    // We only accept one client per server (bsp)
    server.map(h => Established(h.socket.accept(), h))
  }

  private final val bspLogger = com.typesafe.scalalogging.Logger(this.getClass)
  def run(cmd: Commands.Bsp, state: State, scheduler: Scheduler): me.Task[State] = {
    import org.langmeta.lsp.LanguageClient
    import org.langmeta.lsp.LanguageServer
    import org.langmeta.jsonrpc.BaseProtocolMessage

    def uri(handle: ConnectionHandle): String = {
      handle match {
        case w: WindowsLocal => s"local:${w.pipeName}"
        case u: UnixLocal => s"local://${u.path.toString}"
        case t: Tcp => s"tcp://${t.address.getHostString}"
      }
    }

    establishConnection(initServer(cmd, state)).flatMap {
      case Established(socket, handle) =>
        import state.logger
        logger.verbose("Bloop has established connection with a client.")
        val client = new LanguageClient(socket.getOutputStream, bspLogger)
        val servicesProvider = new BloopBspServices(state, client, bspLogger)
        val bloopServices = servicesProvider.services
        val messages = BaseProtocolMessage.fromInputStream(socket.getInputStream)
        val server = new LanguageServer(messages, client, bloopServices, scheduler, bspLogger)

        val connectionURI = uri(handle)
        logger.verbose(s"Bloop bsp server started at '$connectionURI'.")

        server.startTask
          .onErrorRecover { case t: Throwable => logger.trace(t) }
          .map(_ => servicesProvider.latestState)
    }
  }
}
