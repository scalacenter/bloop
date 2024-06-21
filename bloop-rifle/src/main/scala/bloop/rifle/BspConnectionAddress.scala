package bloop.rifle

import java.io.File

sealed abstract class BspConnectionAddress extends Product with Serializable

object BspConnectionAddress {
  final case class Tcp(host: String, port: Int) extends BspConnectionAddress
  final case class UnixDomainSocket(path: File) extends BspConnectionAddress
}
