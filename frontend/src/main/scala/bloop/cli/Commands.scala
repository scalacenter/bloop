package bloop.cli

import java.net.InetAddress
import java.nio.file.Path

import bloop.cli.CliParsers.CommandsMessages
import bloop.io.AbsolutePath
import caseapp.{CommandName, ExtraName, HelpMessage, Recurse}
import caseapp.core.CommandMessages

object Commands {

  /** Represents the most generic command that exists.  */
  trait Command {
    def cliOptions: CliOptions
  }

  /** Represents a command that has gone through an initial layer of validation. */
  sealed trait ValidatedCommand extends Command

  sealed trait ValidatedBsp extends ValidatedCommand
  case class WindowsLocalBsp(pipeName: String, cliOptions: CliOptions) extends ValidatedBsp
  case class UnixLocalBsp(socket: AbsolutePath, cliOptions: CliOptions) extends ValidatedBsp
  case class TcpBsp(host: InetAddress, port: Int, cliOptions: CliOptions) extends ValidatedBsp

  /** Represents a command that is used by the cli and has no user input validation. */
  sealed trait RawCommand extends Command

  sealed trait CompilingCommand extends RawCommand {
    def projects: List[String]
    def reporter: ReporterKind
    def incremental: Boolean
    def pipeline: Boolean
  }

  sealed trait LinkingCommand extends CompilingCommand {
    def main: Option[String]
    def optimize: Option[OptimizerConfig]
  }

  case class Help(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends RawCommand

  case class Autocomplete(
      @Recurse cliOptions: CliOptions = CliOptions.default,
      mode: completion.Mode,
      format: completion.Format,
      command: Option[String],
      project: Option[String]
  ) extends RawCommand

  case class About(
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends RawCommand

  case class Projects(
      @HelpMessage("Print out a dot graph you can pipe into `dot`. By default, false.")
      dotGraph: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends RawCommand

  private[bloop] final val DefaultThreadNumber = 0
  case class Configure(
      @HelpMessage("(deprecated) Set the number of threads used to compile and test all projects.")
      threads: Int = 0,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends RawCommand

  case class Clean(
      @ExtraName("p")
      @ExtraName("project")
      @HelpMessage("The projects to clean (you can specify multiple). If none, all are cleaned.")
      projects: List[String] = Nil,
      @ExtraName("propagate")
      @HelpMessage("Clean a project and all its dependencies. By default, false.")
      includeDependencies: Boolean = false,
      @HelpMessage("Clean a project and all projects depending on it. By default, false.")
      cascade: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends RawCommand

  @CommandName("bsp")
  case class Bsp(
      @HelpMessage("The connection protocol for the bsp server. By default, local.")
      protocol: BspProtocol = BspProtocol.Local,
      @HelpMessage("The server host for the bsp server (TCP only).")
      host: String = "127.0.0.1",
      @HelpMessage("The port for the bsp server (TCP only).")
      port: Int = 5101,
      @HelpMessage("A path to a socket file to communicate through Unix sockets (local only).")
      socket: Option[Path] = None,
      @HelpMessage(
        "A path to a new existing socket file to communicate through Unix sockets (local only).")
      pipeName: Option[String] = None,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends RawCommand

  private lazy val DefaultBatches: ParallelBatches = ParallelBatches.Default
  case class Compile(
      @ExtraName("p")
      @ExtraName("project")
      @HelpMessage("The projects to compile (will be inferred from remaining cli args).")
      projects: List[String] = Nil,
      @HelpMessage("Compile the project incrementally. By default, true.")
      incremental: Boolean = true,
      @HelpMessage("Pipeline the compilation of modules in your build. By default, false.")
      pipeline: Boolean = false,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @ExtraName("w")
      @HelpMessage("Run the command when projects' source files change. By default, false.")
      watch: Boolean = false,
      @HelpMessage("Compile a project and all projects depending on it. By default, false.")
      cascade: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default,
  ) extends CompilingCommand

  case class Test(
      @ExtraName("p")
      @ExtraName("project")
      @HelpMessage("The projects to test (will be inferred from remaining cli args).")
      projects: List[String] = Nil,
      @ExtraName("propagate")
      @HelpMessage("Test a project and all its dependencies. By default, false.")
      includeDependencies: Boolean = false,
      @HelpMessage("Test a project and all projects depending on it. By default, false.")
      cascade: Boolean = false,
      @HelpMessage("Compile the project incrementally. By default, true.")
      incremental: Boolean = true,
      @HelpMessage("Pipeline the compilation of modules in your build. By default, false.")
      pipeline: Boolean = false,
      @ExtraName("o")
      @HelpMessage("The list of test suite filters to test for only.")
      only: List[String] = Nil,
      @HelpMessage("The arguments to pass in to the test framework.")
      args: List[String] = Nil,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @ExtraName("w")
      @HelpMessage("Run the command when projects' source files change. By default, false.")
      watch: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends CompilingCommand

  case class Console(
      @ExtraName("p")
      @ExtraName("project")
      @HelpMessage("The projects to run the console at (will be inferred from remaining cli args).")
      projects: List[String] = Nil,
      @HelpMessage("Compile the project incrementally. By default, true.")
      incremental: Boolean = true,
      @HelpMessage("Pipeline the compilation of modules in your build. By default, false.")
      pipeline: Boolean = false,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @HelpMessage("Start up the console compiling only the target project's dependencies.")
      excludeRoot: Boolean = false,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends CompilingCommand

  case class Run(
      @ExtraName("p")
      @ExtraName("project")
      @HelpMessage("The projects to run (will be inferred from remaining cli args).")
      projects: List[String] = Nil,
      @ExtraName("m")
      @HelpMessage("The main class to run. Leave unset to let bloop select automatically.")
      main: Option[String] = None,
      @HelpMessage("Compile the project incrementally. By default, true.")
      incremental: Boolean = true,
      @HelpMessage("Pipeline the compilation of modules in your build. By default, false.")
      pipeline: Boolean = false,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @HelpMessage("The arguments to pass in to the main class.")
      args: List[String] = Nil,
      @ExtraName("w")
      @HelpMessage("If set, run the command whenever projects' source files change.")
      watch: Boolean = false,
      @HelpMessage("Ignore arguments starting with `-J` and forward them instead.")
      skipJargs: Boolean = false,
      @ExtraName("O")
      @HelpMessage(
        "If an optimizer is used (e.g. Scala Native or Scala.js), run it in `debug` or `release` mode. Defaults to `debug`.")
      optimize: Option[OptimizerConfig] = None,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends LinkingCommand

  case class Link(
      @ExtraName("project")
      @ExtraName("p")
      @HelpMessage("The projects to link (will be inferred from remaining cli args).")
      projects: List[String] = Nil,
      @ExtraName("m")
      @HelpMessage("The main class to link. Leave unset to let bloop select automatically.")
      main: Option[String] = None,
      @HelpMessage("Compile the project incrementally. By default, true.")
      incremental: Boolean = true,
      @HelpMessage("Pipeline the compilation of modules in your build. By default, false.")
      pipeline: Boolean = false,
      @HelpMessage("Pick reporter to show compilation messages. By default, bloop's used.")
      reporter: ReporterKind = BloopReporter,
      @ExtraName("w")
      @HelpMessage("If set, run the command whenever projects' source files change.")
      watch: Boolean = false,
      @ExtraName("O")
      @HelpMessage(
        "Optimization level of the linker. Valid options: `debug` or `release` mode. Defaults to `debug`.")
      optimize: Option[OptimizerConfig] = None,
      @Recurse cliOptions: CliOptions = CliOptions.default
  ) extends LinkingCommand
}
