package bloop.cli

import java.nio.file.Path

import bloop.cli.CliParsers._
import bloop.logging.DebugFilter

import caseapp.ExtraName
import caseapp.HelpMessage
import caseapp.Recurse
import caseapp.ValueDescription
import caseapp.core.help.Help
import caseapp.core.parser.Parser

case class CliOptions(
    @ExtraName("c")
    @HelpMessage(
      "File path to the bloop config directory, defaults to `.bloop` in the current working directory."
    )
    @ValueDescription(".bloop")
    configDir: Option[Path] = None,
    @ExtraName("v")
    @HelpMessage(
      "If set, print the about section at the beginning of the execution. Defaults to false."
    )
    version: Boolean = false,
    @HelpMessage("If set, print out debugging information to stderr. Defaults to false.")
    verbose: Boolean = false,
    @HelpMessage("If set, do not color output. Defaults to false.")
    noColor: Boolean = false,
    @HelpMessage("Debug the execution of a concrete task.")
    debug: List[DebugFilter] = Nil,
    @Recurse common: CommonOptions = CommonOptions.default
)

object CliOptions {
  val default: CliOptions = CliOptions()

  implicit lazy val parser: Parser[CliOptions] = Parser.derive
  implicit lazy val help: Help[CliOptions] = Help.derive
}
