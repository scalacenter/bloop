package bloop.cli

import java.nio.file.Path

import caseapp.{ExtraName, HelpMessage, Recurse, ValueDescription}

case class CliOptions(
    @ExtraName("c")
    @HelpMessage("File path to the bloop config directory.")
    @ValueDescription(".bloop")
    configDir: Option[Path] = None,
    @ExtraName("v")
    @HelpMessage("If set, print the about section at the beginning of the execution.")
    version: Boolean = false,
    @HelpMessage("If set, print out debugging information to stderr.")
    verbose: Boolean = false,
    @ExtraName("f")
    @HelpMessage("If set, print debug messages that contain given string.")
    filter: String = "",
    @Recurse common: CommonOptions = CommonOptions.default,
)

object CliOptions {
  val default = CliOptions()
}
