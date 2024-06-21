package bloop.rifle.internal

import java.io.IOException
import java.net.{ServerSocket, Socket}

object Util {

  def withSocket[T](f: Socket => T): T = {
    var socket: Socket = null
    try {
      socket = new Socket
      f(socket)
    }
    // format: off
    finally {
      if (socket != null)
        try {
          socket.shutdownInput()
          socket.shutdownOutput()
          socket.close()
        }
        catch { case _: IOException => }
    }
    // format: on
  }

  def randomPort(): Int = {
    val s = new ServerSocket(0)
    val port = s.getLocalPort
    s.close()
    port
  }
}
