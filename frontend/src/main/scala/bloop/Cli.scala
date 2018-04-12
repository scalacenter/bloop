package bloop

import java.nio.file.Path

import bloop.bsp.BspServer
import bloop.cli.validation.Validate
import bloop.cli.{CliOptions, CliParsers, Commands, CommonOptions, ExitStatus}
import bloop.engine._
import bloop.logging.{BloopLogger, Logger}
import caseapp.core.{DefaultBaseCommand, Messages}
import com.martiansoftware.nailgun.NGContext
import _root_.monix.eval.Task
import bloop.engine.tasks.Tasks

import scala.util.control.NonFatal

class Cli
object Cli {
  def main(args: Array[String]): Unit = {
    val action = parse(args, CommonOptions.default)
    val exitStatus = run(action, NoPool, args)
    sys.exit(exitStatus.code)
  }

  def nailMain(ngContext: NGContext): Unit = {
    val server = ngContext.getNGServer
    val env = CommonOptions.PrettyProperties.from(ngContext.getEnv())
    val nailgunOptions = CommonOptions(
      in = ngContext.in,
      out = ngContext.out,
      err = ngContext.err,
      ngout = server.out,
      ngerr = server.err,
      workingDirectory = ngContext.getWorkingDirectory,
      env = env
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

    val exitStatus = run(cmd, NailgunPool(ngContext), args)
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
                  // Infer everything after '--' as if they were test framework args
                  run(cmd.copy(args = c.args ++ extraArgs), newCommand.cliOptions)
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

  def run(action: Action, pool: ClientPool, userArgs: Array[String]): ExitStatus = {
    import bloop.io.AbsolutePath
    def getConfigDir(cliOptions: CliOptions): AbsolutePath = {
      cliOptions.configDir
        .map(AbsolutePath.apply)
        .getOrElse(cliOptions.common.workingPath.resolve(".bloop"))
    }

    val cliOptions = action match {
      case r: Run => r.command.cliOptions
      case e: Exit => CliOptions.default
      case p: Print => CliOptions.default.copy(common = p.commonOptions)
    }

    val commonOpts = cliOptions.common
    val configDirectory = getConfigDir(cliOptions)
    val logger =
      BloopLogger.at(configDirectory.syntax, commonOpts.out, commonOpts.err, cliOptions.verbose)
    val currentState = State.loadActiveStateFor(configDirectory, pool, cliOptions.common, logger)

    waitUntilEndOfWorld(action, cliOptions, pool, configDirectory.underlying, logger, userArgs) {
      Interpreter.execute(action, currentState).map { newState =>
        State.stateCache.updateBuild(newState.copy(status = ExitStatus.Ok))
        // Persist successful result on the background for the new state -- it doesn't block!
        Tasks.persist(newState).runAsync(ExecutionContext.ioScheduler)
        newState
      }
    }
  }

  import scala.concurrent.Await
  import scala.concurrent.duration.Duration
  private[bloop] def waitUntilEndOfWorld(
      action: Action,
      cliOptions: CliOptions,
      pool: ClientPool,
      configDirectory: Path,
      logger: Logger,
      userArgs: Array[String]
  )(taskState: Task[State]): ExitStatus = {
    val ngout = cliOptions.common.ngout
    def logElapsed(since: Long): Unit = {
      val elapsed = (System.nanoTime() - since).toDouble / 1e6
      logger.debug(s"Elapsed: $elapsed ms")
    }

    try {
      // Simulate try-catch-finally with monix tasks to time the task execution
      val handle =
        Task
          .now(System.nanoTime())
          .flatMap(start => taskState.materialize.map(s => (s, start)))
          .map { case (state, start) => logElapsed(start); state }
          .dematerialize
          .executeWithOptions(_.enableAutoCancelableRunLoops)
          .runAsync(ExecutionContext.scheduler)

      // Let's cancel tasks (if supported by the underlying implementation) when clients disconnect
      pool.addListener {
        case e: CloseEvent =>
          if (!handle.isCompleted) {
            ngout.println(
              s"Client in $configDirectory disconnected with a '$e' event. Cancelling tasks...")
            handle.cancel()
          }
      }

      val result: State = Await.result(handle, Duration.Inf)
      ngout.println(s"The task for '${userArgs.mkString(" ")}' finished.")
      result.status
    } catch {
      case NonFatal(t) =>
        if (t.getMessage != null)
          logger.error(t.getMessage)
        logger.trace(t)
        ExitStatus.UnexpectedError
    }
  }
}
