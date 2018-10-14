package bloop.cli

import java.nio.file.Path

import bloop.logging.LogContext
import caseapp.{ExtraName, HelpMessage, Recurse, ValueDescription}

case class CliOptions(
    @ExtraName("c")
    @HelpMessage(
      "File path to the bloop config directory, defaults to `.bloop` in the current working directory.")
    @ValueDescription(".bloop")
    configDir: Option[Path] = None,
    @ExtraName("v")
    @HelpMessage(
      "If set, print the about section at the beginning of the execution. Defaults to false.")
    version: Boolean = false,
    @HelpMessage("If set, print out debugging information to stderr. Defaults to false.")
    verbose: Boolean = false,
    @HelpMessage("If set, do not color output. Defaults to false.")
    noColor: Boolean = false,
    @HelpMessage("Debug log context.")
    @ValueDescription("file-watching")
    debug: LogContext = LogContext.All,
    @Recurse common: CommonOptions = CommonOptions.default,
)

object CliOptions {
  val default = CliOptions()
}
