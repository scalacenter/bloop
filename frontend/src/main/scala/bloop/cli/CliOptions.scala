package bloop.cli

import java.nio.file.Path

import caseapp.{ExtraName, HelpMessage, Recurse, ValueDescription}

case class CliOptions(
    @ExtraName("c")
    @HelpMessage("File path to the bloop config directory.")
    @ValueDescription(".bloop-config")
    configDir: Option[Path] = None,
    @HelpMessage("If set, print out debugging information to stderr.")
    verbose: Boolean = false,
    @Recurse common: CommonOptions = CommonOptions.default,
)

object CliOptions {
  val default = CliOptions()
}
