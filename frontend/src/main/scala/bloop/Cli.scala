package bloop

import bloop.bsp.BspServer
import bloop.cli.validation.Validate
import bloop.cli.{CliOptions, CliParsers, Commands, CommonOptions, ExitStatus}
import bloop.engine.{Action, ClientPool, Exit, Interpreter, NailgunPool, NoPool, Print, Run, State}
import bloop.logging.BloopLogger
import caseapp.core.{DefaultBaseCommand, Messages}
import com.martiansoftware.nailgun.NGContext

class Cli
object Cli {
  def main(args: Array[String]): Unit = {
    State.setUpShutdownHoook()
    val action = parse(args, CommonOptions.default)
    val exitStatus = run(action, NoPool)
    sys.exit(exitStatus.code)
  }

  def nailMain(ngContext: NGContext): Unit = {
    val server = ngContext.getNGServer
    val nailgunOptions = CommonOptions(
      in = ngContext.in,
      out = ngContext.out,
      err = ngContext.err,
      ngout = server.out,
      ngerr = server.err,
      workingDirectory = ngContext.getWorkingDirectory,
    )
    val command = ngContext.getCommand
    val args = {
      if (command == "bloop.Cli") ngContext.getArgs
      else command +: ngContext.getArgs
    }
    val cmd = {
      // If no command is given to bloop, we'll receive the script's name.
      if (command == "bloop")
        printErrorAndExit(helpAsked, nailgunOptions)
      else parse(args, nailgunOptions)
    }

    val exitStatus = run(cmd, NailgunPool(ngContext))
    ngContext.exit(exitStatus.code)
  }

  import CliParsers.{CommandsMessages, CommandsParser, BaseMessages, OptionsParser}
  val commands: Seq[String] = CommandsMessages.messages.map(_._1)
  // Getting the name from the sbt generated metadata gives us `bloop-frontend` instead.
  val beforeCommandMessages: Messages[DefaultBaseCommand] = BaseMessages.copy(
    appName = "bloop",
    appVersion = bloop.internal.build.BuildInfo.version,
    progName = "bloop",
    optionsDesc = s"[options] [command] [command-options]"
  )

  private val progName: String = beforeCommandMessages.progName
  private def helpAsked: String =
    s"""${beforeCommandMessages.helpMessage}
       |Available commands: ${commands.mkString(", ")}
       |Type `$progName 'command' --help` for help on an individual command
     """.stripMargin

  private def commandHelpAsked(command: String): String = {
    // We have to do this ourselves because case-app 1.2.0 has a bug in its `ArgsName` handling.
    val messages = CommandsMessages.messagesMap(command)
    val argsName = if (messages.args.exists(_.name.startsWith("project"))) Some("project") else None
    messages.copy(argsNameOption = argsName).helpMessage(beforeCommandMessages.progName, command)
  }

  private def usageAsked: String = {
    s"""${beforeCommandMessages.usageMessage}
       |Available commands: ${commands.mkString(", ")}
       |Type `$progName 'command' --usage` for usage of an individual command
     """.stripMargin
  }

  private def commandUsageAsked(command: String): String =
    CommandsMessages.messagesMap(command).usageMessage(beforeCommandMessages.progName, command)

  private def printErrorAndExit(msg: String, commonOptions: CommonOptions): Print =
    Print(msg, commonOptions, Exit(ExitStatus.InvalidCommandLineOption))

  def parse(args: Array[String], commonOptions: CommonOptions): Action = {
    import caseapp.core.WithHelp

    def inferProjectFromRemaining(args: Seq[String], cmd: String): Either[Action, String] = {
      if (args.isEmpty)
        Left(printErrorAndExit(s"Required project name not specified for '$cmd'.", commonOptions))
      else if (args.size >= 2)
        Left(printErrorAndExit(s"Too many projects have been specified for '$cmd'.", commonOptions))
      else Right(args.head)
    }

    CommandsParser.withHelp.detailedParse(args)(OptionsParser.withHelp) match {
      case Left(err) => printErrorAndExit(err, commonOptions)
      case Right((WithHelp(_, help @ true, _), _, _)) =>
        Print(helpAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(usage @ true, _, _), _, _)) =>
        Print(usageAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(_, _, userOptions), _, commandOpt)) =>
        val newAction = commandOpt map {
          case Left(err) => printErrorAndExit(err, commonOptions)
          case Right((commandName, WithHelp(_, help @ true, _), _, _)) =>
            Print(commandHelpAsked(commandName), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(usage @ true, _, _), _, _)) =>
            Print(commandUsageAsked(commandName), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(_, _, command), remainingArgs, extraArgs)) =>
            // Override common options depending who's the caller of parse (whether nailgun or main)
            def run(command: Commands.RawCommand, cliOptions: CliOptions): Run = {
              if (!cliOptions.version) Run(command, Exit(ExitStatus.Ok))
              else Run(Commands.About(cliOptions), Run(command, Exit(ExitStatus.Ok)))
            }

            // Infer project from the context if the current project is empty.
            def withProject(currentProject: String)(f: String => Action) = {
              if (currentProject.nonEmpty) f(currentProject)
              else {
                inferProjectFromRemaining(remainingArgs, commandName) match {
                  case Left(action) => action
                  case Right(inferredProject) if currentProject.isEmpty => f(inferredProject)
                  case Right(inferredProject) =>
                    printErrorAndExit(
                      s"Detected '$currentProject' and '$inferredProject' are ambiguous projects for '${commandName}'.",
                      commonOptions)
                }
              }
            }

            command match {
              case Left(err) => printErrorAndExit(err, commonOptions)
              case Right(v: Commands.Help) =>
                Print(helpAsked, commonOptions, Exit(ExitStatus.Ok))
              case Right(v: Commands.About) =>
                val newCommand = v.copy(cliOptions = v.cliOptions.copy(common = commonOptions))
                // Disabling version here if user defines it because it has the same semantics
                run(newCommand, newCommand.cliOptions.copy(version = false))
              case Right(c: Commands.Bsp) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                Validate.bsp(newCommand, BspServer.isWindows)
              case Right(c: Commands.Compile) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withProject(c.project) { (p: String) =>
                  val cmd = if (p != c.project) newCommand.copy(project = p) else newCommand
                  run(cmd, newCommand.cliOptions)
                }
              case Right(c: Commands.Autocomplete) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Console) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withProject(c.project) { (p: String) =>
                  val cmd = if (p != c.project) newCommand.copy(project = p) else newCommand
                  run(cmd, newCommand.cliOptions)
                }
              case Right(c: Commands.Test) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withProject(c.project) { (p: String) =>
                  val cmd = if (p != c.project) newCommand.copy(project = p) else newCommand
                  run(cmd, newCommand.cliOptions)
                }
              case Right(c: Commands.Run) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withProject(c.project) { (p: String) =>
                  val cmd0 = if (p != c.project) newCommand.copy(project = p) else newCommand
                  // Infer everything after '--' as if they were execution args
                  val cmd = cmd0.copy(args = c.args ++ extraArgs)
                  run(cmd, newCommand.cliOptions)
                }
              case Right(c: Commands.Clean) =>
                val newCommand = c
                  .copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                  .copy(project = c.project ++ remainingArgs)
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Projects) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Configure) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
            }
        }
        newAction.getOrElse {
          userOptions match {
            case Left(err) => printErrorAndExit(err, commonOptions)
            case Right(cliOptions0) =>
              val cliOptions = cliOptions0.copy(common = commonOptions)
              if (cliOptions.version) Run(Commands.About(cliOptions), Exit(ExitStatus.Ok))
              else {
                val msg = "These flags can only go together with commands!"
                Print(msg, commonOptions, Exit(ExitStatus.InvalidCommandLineOption))
              }
          }
        }
    }
  }

  def run(action: Action, pool: ClientPool): ExitStatus = {
    import bloop.io.AbsolutePath
    def getConfigDir(cliOptions: CliOptions): AbsolutePath = {
      cliOptions.configDir
        .map(AbsolutePath.apply)
        .getOrElse(cliOptions.common.workingPath.resolve(".bloop-config"))
    }

    val cliOptions = action match {
      case r: Run => r.command.cliOptions
      case e: Exit => CliOptions.default
      case p: Print => CliOptions.default.copy(common = p.commonOptions)
    }

    val commonOpts = cliOptions.common
    val configDirectory = getConfigDir(cliOptions)
    val logger = BloopLogger.at(configDirectory.syntax, commonOpts.out, commonOpts.err)
    val state = State.loadActiveStateFor(configDirectory, pool, cliOptions.common, logger)
    val newState = Interpreter.execute(action, state)
    State.stateCache.updateBuild(newState.copy(status = ExitStatus.Ok))
    newState.status
  }
}
