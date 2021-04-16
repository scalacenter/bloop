package bloop.cli

import java.io.{InputStream, PrintStream}
import java.nio.file.{Path, Paths}
import java.util.Properties

import bloop.logging.DebugFilter
import caseapp.core.argparser.{ArgParser, SimpleArgParser}
import caseapp.core.Error
import shapeless.LabelledGeneric

import scala.util.Try
import caseapp.core.parser.Parser
import caseapp.core.default.Default

object CliParsers {
  implicit val inputStreamRead: ArgParser[InputStream] =
    SimpleArgParser.from[InputStream]("stdin")(_ => Right(System.in))
  implicit val printStreamRead: ArgParser[PrintStream] =
    SimpleArgParser.from[PrintStream]("stdout")(_ => Right(System.out))
  implicit val pathParser: ArgParser[Path] = SimpleArgParser.from("path") { supposedPath =>
    val toPath = Try(Paths.get(supposedPath)).toEither
    toPath.left.map(t => Error.MalformedValue("path", s"$supposedPath (${t.getMessage()})"))
  }

  implicit val completionFormatRead: ArgParser[completion.Format] = {
    SimpleArgParser.from[completion.Format]("\"bash\" | \"zsh\" | \"fish\"") {
      case "bash" => Right(completion.BashFormat)
      case "zsh" => Right(completion.ZshFormat)
      case "fish" => Right(completion.FishFormat)
      case w00t => Left(Error.UnrecognizedArgument(s"Unrecognized format: $w00t"))
    }
  }

  implicit val parallelBatchesRead: ArgParser[ParallelBatches] = {
    SimpleArgParser.from[ParallelBatches]("parallel batches") { s =>
      val int: Either[Error, Int] = {
        try Right(s.toInt)
        catch { case _: NumberFormatException => Left(Error.MalformedValue("int", s)) }
      }

      int.flatMap { i =>
        if (i > 0) Right(ParallelBatches(i))
        else Left(Error.Other(s"The number of parallel batches needs to greater than 0."))
      }

    }
  }

  implicit val optimizerConfigRead: ArgParser[OptimizerConfig] = {
    SimpleArgParser.from[OptimizerConfig]("\"debug\" | \"release\"") {
      case "debug" => Right(OptimizerConfig.Debug)
      case "release" => Right(OptimizerConfig.Release)
      case w00t => Left(Error.Other(s"Unrecognized optimizer config: $w00t"))
    }
  }

  implicit val propertiesParser: ArgParser[CommonOptions.PrettyProperties] = {
    SimpleArgParser.from("A properties parser") {
      case whatever => Left(Error.Other("You cannot pass in properties through the command line."))
    }
  }

  val DebugFilterTags =
    "\"all\" | \"file-watching\" | \"compilation\" | \"test\" | \"bsp\" | \"link\""
  implicit val debugFilterParser: ArgParser[DebugFilter] = {
    SimpleArgParser.from[DebugFilter](DebugFilterTags) {
      case "all" => Right(DebugFilter.All)
      case "file-watching" => Right(DebugFilter.FileWatching)
      case "compilation" => Right(DebugFilter.Compilation)
      case "test" => Right(DebugFilter.Test)
      case "bsp" => Right(DebugFilter.Bsp)
      case "link" => Right(DebugFilter.Link)
      case w00t => Left(Error.Other(s"Unrecognized log context: $w00t"))
    }
  }

  implicit val defaultString: Default[String] =
    Default("")

  implicit val defaultInt: Default[Int] =
    Default(0)

  implicit val defaultBoolean: Default[Boolean] =
    Default(false)

  implicit val defaultPrintStream: Default[PrintStream] =
    Default(System.out)

  implicit val defaultInputStream: Default[InputStream] =
    Default(System.in)

  implicit val labelledGenericCommonOptions: LabelledGeneric[CommonOptions] =
    LabelledGeneric.materializeProduct
  implicit val labelledGenericCliOptions: LabelledGeneric[CliOptions] =
    LabelledGeneric.materializeProduct
  implicit val coParser = Parser[CommonOptions]
  implicit val cliParser = Parser[CliOptions]

  implicit val autocompleteParser = Parser[Commands.Autocomplete]
  implicit val aboutParser = Parser[Commands.About]
  implicit val bspParser = Parser[Commands.Bsp]
  implicit val cleanParser = Parser[Commands.Clean]
  implicit val compileParser = Parser[Commands.Compile]
  implicit val configureParser = Parser[Commands.Configure]
  implicit val helpParser = Parser[Commands.Help]
  implicit val projectsParser = Parser[Commands.Projects]
  implicit val runParser = Parser[Commands.Run]
  implicit val testParser = Parser[Commands.Test]

  val BaseMessages: caseapp.core.help.Help[Unit] =
    caseapp.core.help.Help(Nil, "", "", "", None)
  val OptionsParser: caseapp.core.parser.Parser[CliOptions] =
    caseapp.core.parser.Parser[CliOptions]

  val CommandsMessages: caseapp.core.help.CommandsHelp[Commands.RawCommand] =
    caseapp.core.help.CommandsHelp[Commands.RawCommand]
  val CommandsParser: caseapp.core.commandparser.CommandParser[Commands.RawCommand] =
    caseapp.core.commandparser.CommandParser.apply[Commands.RawCommand]
}
