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

  val CommandsParser: CommandParser[Commands.Command] =
    caseapp.core.CommandParser.apply[Commands.Command]
  val BeforeCommandParser: caseapp.core.Parser[DefaultBaseCommand] =
    caseapp.core.Parser.apply[DefaultBaseCommand]
}
