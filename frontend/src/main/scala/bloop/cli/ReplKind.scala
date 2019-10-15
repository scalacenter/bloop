package bloop.cli
import caseapp.core.ArgParser

sealed abstract class ReplKind(val name: String)
case object ScalacRepl extends ReplKind("scalac")
case object AmmoniteRepl extends ReplKind("ammonite")

object ReplKind {
  val repls: List[ReplKind] = List(ScalacRepl, AmmoniteRepl)

  implicit val replKindRead: ArgParser[ReplKind] = {
    ArgParser.instance[ReplKind]("repl") { input =>
      repls.find(_.name == input) match {
        case Some(repl) => Right(repl)
        case None =>
          Left(s"Unrecognized repl: $input. Available: ${repls.map(_.name).mkString(", ")}")
      }
    }
  }
}
