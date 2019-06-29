package bloop.io

import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}

import bloop.sockets.{UnixDomainServerSocket, Win32NamedPipeServerSocket}

sealed trait ServerHandle {
  def uri: URI
  def fireServer: ServerSocket
}

object ServerHandle {
  final case class WindowsLocal(pipeName: String) extends ServerHandle {
    def uri: URI = URI.create(s"local:$pipeName")
    def fireServer: ServerSocket = new Win32NamedPipeServerSocket(pipeName)
    override def toString: String = s"pipe $pipeName"
  }

  final case class UnixLocal(socketFile: AbsolutePath) extends ServerHandle {
    def uri: URI = URI.create(s"local://${socketFile.syntax}")
    def fireServer: ServerSocket = new UnixDomainServerSocket(socketFile.syntax)
    override def toString: String = s"local://${socketFile.syntax}"
  }

  final case class Tcp(address: InetSocketAddress, backlog: Int) extends ServerHandle {
    def uri: URI = URI.create(s"tcp://${address.getHostString}:${address.getPort}")
    def fireServer: ServerSocket =
      new java.net.ServerSocket(address.getPort, backlog, address.getAddress)
    override def toString: String = s"${address.getHostString}:${address.getPort}"
  }

  object Tcp {
    def apply(backlog: Int): Tcp = Tcp(new InetSocketAddress(0), backlog)
    def apply(address: InetAddress, port: Int, backlog: Int): Tcp = {
      Tcp(new InetSocketAddress(address, port), backlog)
    }
  }
}
