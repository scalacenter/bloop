package bloop.cli

import caseapp.{ExtraName, HelpMessage, Hidden, Recurse}

object Commands {
  sealed trait Command {
    def cliOptions: CliOptions
  }

  case class Help(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class About(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Compile(
      @ExtraName("p")
      @HelpMessage("The project to compile.")
      project: String,
      @HelpMessage("If set, it compiles incrementally. By default, true.")
      incremental: Boolean = true,
      @HelpMessage("If set, disable improved error message format. By default, false.")
      scalacstyle: Boolean = false,
      @ExtraName("w")
      @HelpMessage("If set, run the command whenever projects' source files change.")
      watch: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends Command

  case class Projects(
      @ExtraName("dot")
      @HelpMessage(
        "If set, prints out the contents of a dot graph you can pipe into `dot`. Defaults to false")
      dotGraph: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Test(
      @ExtraName("p")
      @HelpMessage("The project to test.")
      project: String,
      @ExtraName("all")
      @HelpMessage("If set, also runs the tests in dependencies. Defaults to true.")
      aggregate: Boolean = false,
      @HelpMessage("If set, disable improved error message format. By default, false.")
      scalacstyle: Boolean = false,
      @ExtraName("w")
      @HelpMessage("If set, run the command whenever projects' source files change.")
      watch: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Clean(
      @ExtraName("p")
      @HelpMessage("The projects to clean.")
      projects: List[String],
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends Command
}
