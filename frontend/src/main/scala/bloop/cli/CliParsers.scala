package bloop.cli

import java.io.{InputStream, PrintStream}
import java.nio.file.{Path, Paths}

import caseapp.CommandParser
import caseapp.core.{ArgParser, DefaultBaseCommand}

import scala.util.Try

object CliParsers {
  implicit val inputStreamRead: ArgParser[InputStream] =
    ArgParser.instance[InputStream]("stdin")(_ => Right(System.in))
  implicit val printStreamRead: ArgParser[PrintStream] =
    ArgParser.instance[PrintStream]("stdout")(_ => Right(System.out))
  implicit val pathParser: ArgParser[Path] = ArgParser.instance("A filepath parser") {
    case supposedPath: String =>
      val toPath = Try(Paths.get(supposedPath)).toEither
      toPath.left.map(t => s"The provided path ${supposedPath} is not valid: '${t.getMessage()}'.")
  }

  implicit val completionModeRead: ArgParser[completion.Mode] = {
    ArgParser.instance[completion.Mode]("mode") {
      case "commands" => Right(completion.Mode.Commands)
      case "projects" => Right(completion.Mode.Projects)
      case "flags" => Right(completion.Mode.Flags)
      case "testsfqcn" => Right(completion.Mode.TestsFQCN)
      case "mainsfqcn" => Right(completion.Mode.MainsFQCN)
      case w00t => Left(s"Unrecognized mode: $w00t")
    }
  }

  implicit val completionFormatRead: ArgParser[completion.Format] = {
    ArgParser.instance[completion.Format]("format") {
      case "bash" => Right(completion.BashFormat)
      case "zsh" => Right(completion.ZshFormat)
      case w00t => Left(s"Unrecognized format: $w00t")
    }
  }

  val BaseMessages: caseapp.core.Messages[DefaultBaseCommand] =
    caseapp.core.Messages[DefaultBaseCommand]
  val OptionsParser: caseapp.core.Parser[CliOptions] =
    caseapp.core.Parser.apply[CliOptions]

  val CommandsMessages: caseapp.core.CommandsMessages[Commands.RawCommand] =
    caseapp.core.CommandsMessages[Commands.RawCommand]
  val CommandsParser: CommandParser[Commands.RawCommand] =
    caseapp.core.CommandParser.apply[Commands.RawCommand]
}
