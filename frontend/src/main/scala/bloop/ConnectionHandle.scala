package bloop

import java.net.{InetAddress, InetSocketAddress, ServerSocket, URI}

import bloop.ConnectionHandle.{Tcp, UnixLocal, WindowsLocal}
import bloop.io.AbsolutePath
import bloop.sockets.{UnixDomainServerSocket, Win32NamedPipeServerSocket}

sealed trait ConnectionHandle extends AutoCloseable {
  def serverSocket: ServerSocket

  final def uri: URI = {
    val identifier = this match {
      case WindowsLocal(pipe, _) =>
        s"local:$pipe"
      case UnixLocal(socket, _) =>
        s"local://${socket.syntax}"
      case Tcp(address, _) =>
        s"tcp://${address.getHostString}:${address.getPort}"
    }
    URI.create(identifier)
  }

  final override def close(): Unit = serverSocket.close()

  final override def toString: String = this match {
    case WindowsLocal(pipe, _) =>
      s"pipe $pipe"
    case UnixLocal(socket, _) =>
      socket.toString
    case Tcp(address, serverSocket) =>
      s"$address:${serverSocket.getLocalPort}"
  }
}

object ConnectionHandle {
  def windows(pipeName: String): ConnectionHandle = {
    val server = new Win32NamedPipeServerSocket(pipeName)
    WindowsLocal(pipeName, server)
  }

  def unix(socket: AbsolutePath): ConnectionHandle = {
    val server = new UnixDomainServerSocket(socket.toString)
    UnixLocal(socket, server)
  }

  def tcp(address: InetAddress, portNumber: Int, backlog: Int): ConnectionHandle = {
    val socketAddress = new InetSocketAddress(address, portNumber)
    val server = new java.net.ServerSocket(portNumber, backlog, address)
    Tcp(socketAddress, server)
  }

  final case class WindowsLocal(pipeName: String, serverSocket: ServerSocket)
      extends ConnectionHandle

  final case class UnixLocal(socketFile: AbsolutePath, serverSocket: ServerSocket)
      extends ConnectionHandle

  final case class Tcp(address: InetSocketAddress, serverSocket: ServerSocket)
      extends ConnectionHandle
}
