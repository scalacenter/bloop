package bloop.cli

import java.io.{InputStream, PrintStream}
import java.nio.file.{Path, Paths}
import java.util.Properties

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

  implicit val completionFormatRead: ArgParser[completion.Format] = {
    ArgParser.instance[completion.Format]("format") {
      case "bash" => Right(completion.BashFormat)
      case "zsh" => Right(completion.ZshFormat)
      case w00t => Left(s"Unrecognized format: $w00t")
    }
  }

  implicit val propertiesParser: ArgParser[CommonOptions.PrettyProperties] = {
    ArgParser.instance("A properties parser") {
      case whatever => Left("You cannot pass in properties through the command line.")
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
