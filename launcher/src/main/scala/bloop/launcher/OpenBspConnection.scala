package bloop.launcher

import java.nio.file.Path

sealed trait OpenBspConnection

object OpenBspConnection {
  final case class Tcp(host: String, port: Int) extends OpenBspConnection
  final case class UnixLocal(socketPath: Path) extends OpenBspConnection
}

