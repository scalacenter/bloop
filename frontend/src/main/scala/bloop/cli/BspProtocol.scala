package bloop.cli

sealed trait BspProtocol

object BspProtocol {
  case object Local extends BspProtocol
  case object Tcp extends BspProtocol
}
