package bloop.io

import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}

import bloop.sockets.{UnixDomainServerSocket, Win32NamedPipeServerSocket}

sealed trait ServerHandle {
  def uri: URI
  def server: ServerSocket
}

object ServerHandle {
  final case class WindowsLocal(pipeName: String) extends ServerHandle {
    val server: ServerSocket = new Win32NamedPipeServerSocket(pipeName)
    def uri: URI = URI.create(s"local:$pipeName")
    override def toString: String = s"pipe $pipeName"
  }

  final case class UnixLocal(socketFile: AbsolutePath) extends ServerHandle {
    val server: ServerSocket = new UnixDomainServerSocket(socketFile.syntax)
    def uri: URI = URI.create(s"local://${socketFile.syntax}")
    override def toString: String = s"local://${socketFile.syntax}"
  }

  final case class Tcp(address: InetSocketAddress, backlog: Int) extends ServerHandle {
    val server: ServerSocket = new ServerSocket(address.getPort, backlog, address.getAddress)
    def uri: URI = URI.create(s"tcp://${address.getHostString}:${server.getLocalPort}")
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
