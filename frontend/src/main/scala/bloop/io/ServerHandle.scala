package bloop.io

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel

sealed trait ServerHandle {
  def uri: String
  def server: ServerSocket
}

object ServerHandle {
  final case class UnixLocal(socketFile: AbsolutePath) extends ServerHandle {
    val server: ServerSocket = {
      val addr = UnixDomainSocketAddress.of(socketFile.syntax)
      val s = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
      s.bind(addr)
      libdaemonjvm.Util.serverSocketFromChannel(s)
    }
    def uri: String = s"local://${socketFile.syntax}"
    override def toString: String = s"local://${socketFile.syntax}"
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
