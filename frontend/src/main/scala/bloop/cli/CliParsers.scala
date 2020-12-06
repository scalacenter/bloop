package bloop.cli

import java.io.{InputStream, PrintStream}
import java.nio.file.{Path, Paths}
import java.util.Properties

import bloop.logging.DebugFilter
import caseapp.CommandParser

import shapeless.LabelledGeneric

import scala.util.Try
import caseapp.core.argparser.SimpleArgParser
import caseapp.core.argparser.ArgParser

import caseapp.core.Error
import caseapp.core.parser.Parser

object CliParsers {
  implicit val inputStreamRead: ArgParser[InputStream] =
    SimpleArgParser.from[InputStream]("stdin")(_ => Right(System.in))
  implicit val printStreamRead: ArgParser[PrintStream] =
    SimpleArgParser.from[PrintStream]("stdout")(_ => Right(System.out))
  
  implicit val pathParser: ArgParser[Path] = SimpleArgParser.from("path") {
    case supposedPath: String =>
      val toPath = Try(Paths.get(supposedPath)).toEither
      toPath.left.map(t => Error.Other(s"The provided path ${supposedPath} is not valid: '${t.getMessage()}'."))
  }

  implicit val completionFormatRead: ArgParser[completion.Format] = {
    SimpleArgParser.from[completion.Format]("\"bash\" | \"zsh\" | \"fish\"") {
      case "bash" => Right(completion.BashFormat)
      case "zsh" => Right(completion.ZshFormat)
      case "fish" => Right(completion.FishFormat)
      case w00t => Left(Error.Other(s"Unrecognized format: $w00t"))
    }
  }

  implicit val parallelBatchesRead: ArgParser[ParallelBatches] = {
    SimpleArgParser.from[ParallelBatches]("parallel batches") {
      case s: String =>
        val int: Either[Error, Int] = {
          try Right(s.toInt)
          catch { case _: NumberFormatException => Left(Error.Other(s"Malformed integer: $s")) }
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

  // import shapeless.{HNil, CNil, :+:, ::, Coproduct}
  // implicit val implicitHNil: Implicit[HNil] = Implicit.hnil
  // implicit val implicitNone: Implicit[None.type] = Implicit.instance(None)
  // implicit val implicitNoneCnil: Implicit[None.type :+: CNil] =
  //   Implicit.instance(Coproduct(None))

  // implicit val implicitOptionDefaultString: Implicit[Option[Default[String]]] =
  //   Implicit.instance(Some(caseapp.core.Defaults.string))

  // implicit val implicitOptionDefaultInt: Implicit[Option[Default[Int]]] =
  //   Implicit.instance(Some(caseapp.core.Defaults.int))

  // implicit val implicitOptionDefaultBoolean: Implicit[Option[Default[Boolean]]] =
  //   Implicit.instance(Some(caseapp.core.Defaults.boolean))

  // implicit val implicitDefaultBoolean: Implicit[Default[Boolean]] =
  //   Implicit.instance(caseapp.core.Defaults.boolean)

  // implicit val implicitOptionDefaultOptionPath: Implicit[Option[Default[Option[Path]]]] =
  //   Implicit.instance(None)

  // implicit val implicitOptionDefaultPrintStream: Implicit[Option[Default[PrintStream]]] =
  //   Implicit.instance(Some(Default.instance[PrintStream](System.out)))

  // implicit val implicitOptionDefaultInputStream: Implicit[Option[Default[InputStream]]] =
  //   Implicit.instance(Some(Default.instance[InputStream](System.in)))

  // implicit val labelledGenericCommonOptions: LabelledGeneric[CommonOptions] =
  //   LabelledGeneric.materializeProduct
  // implicit val labelledGenericCliOptions: LabelledGeneric[CliOptions] =
  //   LabelledGeneric.materializeProduct
  implicit val coParser: Parser[CommonOptions] = Parser[CommonOptions]
  implicit val cliParser: Parser[CliOptions] = Parser[CliOptions]

  implicit val autocompleteParser: Parser[Commands.Autocomplete] = Parser[Commands.Autocomplete]
  implicit val aboutParser: Parser[Commands.About] = Parser[Commands.About]
  implicit val bspParser: Parser[Commands.Bsp] = Parser[Commands.Bsp]
  implicit val cleanParser: Parser[Commands.Clean] = Parser[Commands.Clean]
  implicit val compileParser: Parser[Commands.Compile] = Parser[Commands.Compile]
  implicit val configureParser: Parser[Commands.Configure] = Parser[Commands.Configure]
  implicit val helpParser: Parser[Commands.Help] = Parser[Commands.Help]
  implicit val projectsParser: Parser[Commands.Projects] = Parser[Commands.Projects]
  implicit val runParser: Parser[Commands.Run] = Parser[Commands.Run]
  implicit val testParser: Parser[Commands.Test] = Parser[Commands.Test]

  // val BaseMessages: caseapp.core.Messages[DefaultBaseCommand] =
  //   caseapp.core.Messages[DefaultBaseCommand]
  val OptionsParser: Parser[CliOptions] = Parser[CliOptions]

  // val CommandsMessages: caseapp.core.CommandsMessages[Commands.RawCommand] =
  //   caseapp.core.CommandsMessages[Commands.RawCommand]
  val CommandsParser: CommandParser[Commands.RawCommand] =
    CommandParser[Commands.RawCommand]
}
