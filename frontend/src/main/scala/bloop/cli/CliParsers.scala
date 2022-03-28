package bloop.cli

import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path
import java.nio.file.Paths

import scala.util.Try

import bloop.logging.DebugFilter

import caseapp.core.Error
import caseapp.core.argparser.ArgParser
import caseapp.core.argparser.SimpleArgParser
import caseapp.core.default.Default

object CliParsers {
  implicit lazy val inputStreamRead: ArgParser[InputStream] =
    SimpleArgParser.from[InputStream]("stdin")(_ => Right(System.in))
  implicit lazy val printStreamRead: ArgParser[PrintStream] =
    SimpleArgParser.from[PrintStream]("stdout")(_ => Right(System.out))
  implicit lazy val pathParser: ArgParser[Path] = SimpleArgParser.from("path") { supposedPath =>
    val toPath = Try(Paths.get(supposedPath)).toEither
    toPath.left.map(t => Error.MalformedValue("path", s"$supposedPath (${t.getMessage()})"))
  }

  implicit lazy val completionFormatRead: ArgParser[completion.Format] = {
    SimpleArgParser.from[completion.Format]("\"bash\" | \"zsh\" | \"fish\"") {
      case "bash" => Right(completion.BashFormat)
      case "zsh" => Right(completion.ZshFormat)
      case "fish" => Right(completion.FishFormat)
      case w00t => Left(Error.UnrecognizedArgument(s"Unrecognized format: $w00t"))
    }
  }

  implicit lazy val parallelBatchesRead: ArgParser[ParallelBatches] = {
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

  implicit lazy val optimizerConfigRead: ArgParser[OptimizerConfig] = {
    SimpleArgParser.from[OptimizerConfig]("\"debug\" | \"release\"") {
      case "debug" => Right(OptimizerConfig.Debug)
      case "release" => Right(OptimizerConfig.Release)
      case w00t => Left(Error.Other(s"Unrecognized optimizer config: $w00t"))
    }
  }

  implicit lazy val propertiesParser: ArgParser[CommonOptions.PrettyProperties] = {
    SimpleArgParser.from("A properties parser") {
      case whatever => Left(Error.Other("You cannot pass in properties through the command line."))
    }
  }

  val DebugFilterTags =
    "\"all\" | \"file-watching\" | \"compilation\" | \"test\" | \"bsp\" | \"link\""
  implicit lazy val debugFilterParser: ArgParser[DebugFilter] = {
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

  implicit lazy val defaultString: Default[String] =
    Default("")

  implicit lazy val defaultInt: Default[Int] =
    Default(0)

  implicit lazy val defaultBoolean: Default[Boolean] =
    Default(false)

  implicit lazy val defaultPrintStream: Default[PrintStream] =
    Default(System.out)

  implicit lazy val defaultInputStream: Default[InputStream] =
    Default(System.in)
}
