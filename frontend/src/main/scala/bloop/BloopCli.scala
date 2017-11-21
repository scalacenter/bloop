package bloop

import bloop.cli.{CliParsers, Commands, CommonOptions, ExitStatus}
import bloop.engine.{Action, Exit, Interpreter, Print, Run}
import com.martiansoftware.nailgun

object BloopCli {
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

  def parse(args: Array[String], commonOptions: CommonOptions): Action = {
    import caseapp.core.WithHelp
    import CliParsers.{CommandsParser, BeforeCommandParser}
    def printAndExit(msg: String): Print =
      Print(msg, commonOptions, Exit(ExitStatus.InvalidCommandLineOption))

    CommandsParser.withHelp.detailedParse(args)(BeforeCommandParser.withHelp) match {
      case Left(err) => printAndExit(err)
      case Right((WithHelp(_, help @ true, _), _, _)) =>
        Print("", commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(usage @ true, _, _), _, _)) =>
        Print("", commonOptions, Exit(ExitStatus.Ok))
      case Right((_, _, commandOpt)) =>
        val newAction = commandOpt map {
          case Left(err) => printAndExit(err)
          case Right((commandName, WithHelp(_, help @ true, _), _, _)) =>
            Print("", commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(usage @ true, _, _), _, _)) =>
            Print("", commonOptions, Exit(ExitStatus.Ok))
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
