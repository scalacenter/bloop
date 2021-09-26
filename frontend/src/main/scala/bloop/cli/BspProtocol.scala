package bloop.cli

import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import caseapp.core.Error

sealed abstract class BspProtocol(val name: String)

object BspProtocol {
  case object Local extends BspProtocol("local")
  case object Tcp extends BspProtocol("tcp")

  val protocols: List[BspProtocol] = List(Local, Tcp)

  implicit val bspProtocolRead: ArgParser[BspProtocol] = {
    SimpleArgParser.from[BspProtocol]("protocol") { input =>
      protocols.find(_.name == input) match {
        case Some(protocol) => Right(protocol)
        case None => Left(Error.MalformedValue("protocol", input))
      }
    }
  }

}
