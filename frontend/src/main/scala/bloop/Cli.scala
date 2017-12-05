package bloop

import bloop.cli._
import bloop.engine._
import bloop.logging.Logger
import caseapp.core.{DefaultBaseCommand, Messages}
import com.martiansoftware.nailgun

class Cli
object Cli {

  private val logger = Logger.get

  def main(args: Array[String]): Unit = {
    val action = parse(args, CommonOptions.default)
    val exitStatus =
      ExecutionContext.withFixedThreadPool { run(action, logger)(_) }
    sys.exit(exitStatus.code)
  }

  def nailMain(ngContext: nailgun.NGContext): Unit = {
    val nailgunOptions = CommonOptions(
      in = ngContext.in,
      out = ngContext.out,
      err = ngContext.err,
      workingDirectory = ngContext.getWorkingDirectory,
    )
    val command = ngContext.getCommand
    val args =
      if (command == "bloop.Cli") ngContext.getArgs
      else command +: ngContext.getArgs
    val cmd = parse(args, nailgunOptions)
    val exitStatus = run(cmd, logger)(Server.executionContext)
    ngContext.exit(exitStatus.code)
  }

  import CliParsers.{CommandsMessages, CommandsParser, BaseMessages, OptionsParser}
  val commands: Seq[String] = CommandsMessages.messages.map(_._1)
  val beforeCommandMessages: Messages[DefaultBaseCommand] = BaseMessages.copy(
    appName = bloop.internal.build.BuildInfo.name,
    appVersion = bloop.internal.build.BuildInfo.version,
    progName = bloop.internal.build.BuildInfo.name,
    optionsDesc = s"[options] [command] [command-options]"
  )

  private val progName: String = beforeCommandMessages.progName
  private def helpAsked: String =
    s"""${beforeCommandMessages.helpMessage}
       |Available commands: ${commands.mkString(", ")}
       |Type `$progName 'command' --help` for help on an individual command
     """.stripMargin

  private def commandHelpAsked(command: String): String =
    CommandsMessages.messagesMap(command).helpMessage(beforeCommandMessages.progName, command)

  private def usageAsked: String = {
    s"""${beforeCommandMessages.usageMessage}
       |Available commands: ${commands.mkString(", ")}
       |Type `$progName 'command' --usage` for usage of an individual command
     """.stripMargin
  }

  private def commandUsageAsked(command: String): String =
    CommandsMessages.messagesMap(command).usageMessage(beforeCommandMessages.progName, command)

  def parse(args: Array[String], commonOptions: CommonOptions): Action = {
    import caseapp.core.WithHelp
    def printErrorAndExit(msg: String): Print =
      Print(msg, commonOptions, Exit(ExitStatus.InvalidCommandLineOption))

    CommandsParser.withHelp.detailedParse(args)(OptionsParser.withHelp) match {
      case Left(err) => printErrorAndExit(err)
      case Right((WithHelp(_, help @ true, _), _, _)) =>
        Print(helpAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(usage @ true, _, _), _, _)) =>
        Print(usageAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(_, _, userOptions), _, commandOpt)) =>
        val newAction = commandOpt map {
          case Left(err) => printErrorAndExit(err)
          case Right((commandName, WithHelp(_, help @ true, _), _, _)) =>
            Print(commandHelpAsked(commandName), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(usage @ true, _, _), _, _)) =>
            Print(commandUsageAsked(commandName), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(_, _, command), _, _)) =>
            // Override common options depending who's the caller of parse (whether nailgun or main)
            def run(command: Commands.Command, cliOptions: CliOptions): Run = {
              if (!cliOptions.version) Run(command, Exit(ExitStatus.Ok))
              else Run(Commands.About(cliOptions), Run(command, Exit(ExitStatus.Ok)))
            }

            command match {
              case Left(err) => printErrorAndExit(err)
              case Right(v: Commands.Help) =>
                Print(helpAsked, commonOptions, Exit(ExitStatus.Ok))
              case Right(v: Commands.About) =>
                val newCommand = v.copy(cliOptions = v.cliOptions.copy(common = commonOptions))
                // Disabling version here if user defines it because it has the same semantics
                run(newCommand, newCommand.cliOptions.copy(version = false))
              case Right(c: Commands.Compile) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Test) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Clean) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Projects) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
            }
        }
        newAction.getOrElse {
          userOptions match {
            case Left(err) => printErrorAndExit(err)
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

  def run(action: Action, logger: Logger)(implicit ec: ExecutionContext): ExitStatus =
    Interpreter.execute(action, logger)
}
