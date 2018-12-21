package bloop.launcher.bsp

import java.nio.file.Path

sealed trait BspConnection

object BspConnection {
  final case class Tcp(host: String, port: Int) extends BspConnection
  final case class UnixLocal(socketPath: Path) extends BspConnection
}

