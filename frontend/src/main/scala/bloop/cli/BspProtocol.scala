package bloop.cli

import caseapp.core.ArgParser

sealed abstract class BspProtocol(val name: String)

object BspProtocol {
  case object Local extends BspProtocol("local")
  case object Tcp extends BspProtocol("tcp")

  val protocols: List[BspProtocol] = List(Local, Tcp)

  implicit val bspProtocolRead: ArgParser[BspProtocol] = {
    ArgParser.instance[BspProtocol]("protocol") { input =>
      protocols.find(_.name == input) match {
        case Some(protocol) => Right(protocol)
        case None => Left("Unrecognized protocol: $input")
      }
    }
  }

}
