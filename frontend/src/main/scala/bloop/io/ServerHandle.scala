package bloop.io

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

sealed trait ServerHandle {
  def uri: String
  def server: ServerSocket
}

object ServerHandle {
  final case class UnixLocal(socketFile: AbsolutePath) extends ServerHandle {
    val server: ServerSocket = {
      val addr = UnixDomainSocketAddress.of(socketFile.syntax)
      deleteStaleSocketFile(socketFile.underlying, addr)
      val s = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      s.bind(addr)
      libdaemonjvm.Util.serverSocketFromChannel(s)
    }
    def uri: String = s"local://${socketFile.syntax}"
    override def toString: String = s"local://${socketFile.syntax}"
  }

  /**
   * Removes a socket file left behind by a server that died without cleaning it
   * up, which would otherwise make `bind` fail with "address already in use".
   *
   * Only a socket file that nothing listens on is removed. A successful connection
   * means a live server owns the address, so the file is left alone and `bind` is
   * allowed to fail rather than silently stealing the address from that server.
   */
  private def deleteStaleSocketFile(socketPath: Path, addr: UnixDomainSocketAddress): Unit = {
    val isSocketFile =
      try Files.readAttributes(socketPath, classOf[BasicFileAttributes]).isOther()
      catch { case _: IOException => false }

    if (isSocketFile && !isListening(addr)) {
      try {
        Files.deleteIfExists(socketPath)
        ()
      } catch { case _: IOException => () }
    }
  }

  private def isListening(addr: UnixDomainSocketAddress): Boolean = {
    val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
    try channel.connect(addr)
    catch { case _: IOException => false }
    finally channel.close()
  }

  final case class Tcp(address: InetSocketAddress, backlog: Int) extends ServerHandle {
    val server: ServerSocket = new ServerSocket(address.getPort, backlog, address.getAddress)
    def uri: String = s"tcp://${address.getHostString}:${server.getLocalPort}"
    override def toString: String = s"${address.getHostString}:${server.getLocalPort}"
  }

  object Tcp {
    def apply(): Tcp = Tcp(new InetSocketAddress(0), 10)
    def apply(backlog: Int): Tcp = Tcp(new InetSocketAddress(0), backlog)
    def apply(address: InetAddress, port: Int, backlog: Int): Tcp = {
      Tcp(new InetSocketAddress(address, port), backlog)
    }
  }
}
