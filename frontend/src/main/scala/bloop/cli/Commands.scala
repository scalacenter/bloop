package bloop.cli

import bloop.engine.ExecutionContext
import caseapp.{ExtraName, HelpMessage, Recurse}

object Commands {
  sealed trait Command {
    def cliOptions: CliOptions
  }

  sealed trait CoreCommand extends Command {
    def project: String
    def reporter: ReporterKind
  }

  case class Help(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class About(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Projects(
      @ExtraName("dot")
      @HelpMessage("Print out a dot graph you can pipe into `dot`. By default, false.")
      dotGraph: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Configure(
      @ExtraName("parallelism")
      @HelpMessage("Set the number of threads used for parallel compilation and test execution.")
      threads: Int = ExecutionContext.executor.getCorePoolSize,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Clean(
      @ExtraName("p")
      @HelpMessage("The projects to clean.")
      projects: List[String],
      @HelpMessage("Do not run clean for dependencies. By default, false.")
      isolated: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends Command

  case class Bsp(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends Command

  case class Compile(
      @ExtraName("p")
      @HelpMessage("The project to compile.")
      project: String,
      @HelpMessage("Compile the project incrementally. By default, true.")
      incremental: Boolean = true,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @ExtraName("w")
      @HelpMessage("Run the command when projects' source files change. By default, false.")
      watch: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends CoreCommand

  case class Test(
      @ExtraName("p")
      @HelpMessage("The project to test.")
      project: String,
      @HelpMessage("Do not run tests for dependencies. By default, false.")
      isolated: Boolean = false,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @ExtraName("w")
      @HelpMessage("Run the command when projects' source files change. By default, false.")
      watch: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends CoreCommand

  case class Console(
      @ExtraName("p")
      @HelpMessage("The project for which to start the console.")
      project: String,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @HelpMessage("Start up the console compiling only the target project's dependencies.")
      excludeRoot: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends CoreCommand

  case class Run(
      @ExtraName("p")
      @HelpMessage("The project to run.")
      project: String,
      @ExtraName("m")
      @HelpMessage("The main class to run. Leave unset to let bloop select automatically.")
      main: Option[String] = None,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @HelpMessage("The arguments to pass to the application")
      args: List[String] = Nil,
      @ExtraName("w")
      @HelpMessage("If set, run the command whenever projects' source files change.")
      watch: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends CoreCommand
}
