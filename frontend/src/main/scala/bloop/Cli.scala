package bloop

import bloop.cli.{CliParsers, Commands, CommonOptions, ExitStatus}
import bloop.engine.{Action, Exit, Interpreter, Print, Run}
import caseapp.core.{DefaultBaseCommand, Messages}
import com.martiansoftware.nailgun

object Cli {
  def main(args: Array[String]): Unit = {
    val action = parse(args, CommonOptions.default)
    val exit = run(action)
    sys.exit(exit.code)
  }

  def nailMain(ngContext: nailgun.NGContext): Unit = {
    val nailgunOptions = CommonOptions(
      in = ngContext.in,
      out = ngContext.out,
      err = ngContext.err,
      workingDirectory = ngContext.getWorkingDirectory,
    )
    val cmd = parse(ngContext.getArgs, nailgunOptions)
    val exit = run(cmd)
    ngContext.exit(exit.code)
  }

  import CliParsers.{CommandsMessages, CommandsParser, BaseMessages, BaseParser}
  val commands: Seq[String] = CommandsMessages.messages.map(_._1)
  val beforeCommandMessages: Messages[DefaultBaseCommand] = BaseMessages.copy(
    appName = "bloop",
    appVersion = "0.1.0",
    progName = "bloop",
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
    def printAndExit(msg: String): Print =
      Print(msg, commonOptions, Exit(ExitStatus.InvalidCommandLineOption))

    CommandsParser.withHelp.detailedParse(args)(BaseParser.withHelp) match {
      case Left(err) => printAndExit(err)
      case Right((WithHelp(_, help @ true, _), _, _)) =>
        Print(helpAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(usage @ true, _, _), _, _)) =>
        Print(usageAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((_, _, commandOpt)) =>
        val newAction = commandOpt map {
          case Left(err) => printAndExit(err)
          case Right((commandName, WithHelp(_, help @ true, _), _, _)) =>
            Print(commandHelpAsked(commandName), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(usage @ true, _, _), _, _)) =>
            Print(commandUsageAsked(commandName), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(_, _, command), _, _)) =>
            // Override common options depending who's the caller of parse (whether nailgun or main)
            def run(command: Commands.Command): Run = Run(command, Exit(ExitStatus.Ok))
            command match {
              case Left(err) => printAndExit(err)
              case Right(v: Commands.Version) =>
                run(v.copy(cliOptions = v.cliOptions.copy(common = commonOptions)))
              case Right(c: Commands.Compile) =>
                run(c.copy(cliOptions = c.cliOptions.copy(common = commonOptions)))
              case Right(c: Commands.Clean) =>
                run(c.copy(cliOptions = c.cliOptions.copy(common = commonOptions)))
            }
        }
        newAction.getOrElse(Print("", commonOptions, Exit(ExitStatus.Ok)))
    }
  }

  def run(action: Action): ExitStatus = Interpreter.execute(action)
}
