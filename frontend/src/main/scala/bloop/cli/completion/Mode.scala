package bloop.cli.completion

import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import caseapp.core.Error

sealed abstract class Mode(val name: String)

/** The kind of items that should be returned for autocompletion */
object Mode {

  /** Query autocompletion of commands */
  case object Commands extends Mode("commands")

  /** Query autocompletion of project names */
  case object Projects extends Mode("projects")

  /** Query autocompletion of project-bound commands */
  case object ProjectBoundCommands extends Mode("project-commands")

  /** Query autocompletion of command flags */
  case object Flags extends Mode("flags")

  /** Query supported error reporters */
  case object Reporters extends Mode("reporters")

  /** Query communication protocols supported by BSP */
  case object Protocols extends Mode("protocols")

  /** Query autocompletion of test names */
  case object TestsFQCN extends Mode("testsfqcn")

  /** Query autocompletion of main classes */
  case object MainsFQCN extends Mode("mainsfqcn")

  val modes: List[Mode] =
    List(
      Commands,
      Projects,
      ProjectBoundCommands,
      Flags,
      Reporters,
      Protocols,
      TestsFQCN,
      MainsFQCN
    )

  implicit val completionModeRead: ArgParser[Mode] = {
    SimpleArgParser.from[Mode]("mode") { input =>
      modes.find(_.name == input) match {
        case Some(mode) => Right(mode)
        case None => Left(Error.Other(s"Unrecognized mode: $input"))
      }
    }
  }
}
