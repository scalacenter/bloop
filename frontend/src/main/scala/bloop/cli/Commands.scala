package bloop.cli

import java.nio.file.Path

import caseapp.CommandParser
import caseapp.core.CommandsMessages

object Commands {
  sealed trait Command
  object Command extends CustomParsers {
    implicit val commandParser: CommandParser[Command]       = implicitly[CommandParser[Command]]
    implicit val commandsMessages: CommandsMessages[Command] = implicitly[CommandsMessages[Command]]
  }

  case class Compile(
      config: Path,
      project: String,
      batch: Boolean = false,
      parallel: Boolean = true
  ) extends Command

  case class Clean(
      config: Path,
      projects: List[String]
  ) extends Command
}
