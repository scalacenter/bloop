package bloop.cli

import caseapp.{ExtraName, HelpMessage, Recurse}

object Commands {
  sealed trait Command
  case class Version(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Compile(
      @ExtraName("p")
      @HelpMessage("Print bloop's version number and exit.")
      project: String,
      incremental: Boolean = true,
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends Command


  case class Clean(
      @HelpMessage("The projects to be cleaned.")
      projects: List[String],
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends Command
}
