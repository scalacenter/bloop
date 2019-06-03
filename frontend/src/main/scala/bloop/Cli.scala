package bloop

import java.io.{InputStream, PrintStream}
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

import bloop.bsp.BspServer
import bloop.io.AbsolutePath
import bloop.util.CrossPlatform
import bloop.data.ClientInfo
import bloop.cli.{CliOptions, CliParsers, Commands, CommonOptions, ExitStatus, Validate}
import bloop.engine._
import bloop.engine.tasks.Tasks
import bloop.logging.{BloopLogger, DebugFilter, Logger}

import caseapp.core.{DefaultBaseCommand, Messages}
import com.martiansoftware.nailgun.NGContext
import _root_.monix.eval.Task

import scala.concurrent.Promise
import scala.util.control.NonFatal
import caseapp.core.CommandsMessages
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import scala.util.Try
import monix.execution.atomic.AtomicBoolean

class Cli
object Cli {

  def main(args: Array[String]): Unit = {
    val action = parse(args, CommonOptions.default)
    val exitStatus = run(action, NoPool)
    sys.exit(exitStatus.code)
  }

  def reflectMain(
      args: Array[String],
      cwd: Path,
      in: InputStream,
      out: PrintStream,
      err: PrintStream,
      props: java.util.Properties,
      cancel: CompletableFuture[java.lang.Boolean]
  ): Int = {
    val env = CommonOptions.PrettyProperties.from(props)
    val nailgunOptions = CommonOptions(
      in = in,
      out = out,
      err = err,
      ngout = out,
      ngerr = err,
      workingDirectory = cwd.toAbsolutePath.toString,
      env = env
    )

    val cmd = parse(args, nailgunOptions)
    val exitStatus = run(cmd, NoPool, cancel)
    exitStatus.code
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

  private def withNonEmptyProjects(
      currentProjects: List[String],
      commandName: String,
      remainingArgs: Seq[String],
      commonOptions: CommonOptions
  )(f: List[String] => Action): Action = {
    // We interpret remaining args as projects too
    val potentialProjects = currentProjects ++ remainingArgs
    if (potentialProjects.nonEmpty) f(potentialProjects)
    else {
      printErrorAndExit(s"Required project name not specified for '$commandName'.", commonOptions)
    }
  }

  def parse(args: Array[String], commonOptions: CommonOptions): Action = {
    import caseapp.core.WithHelp
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
                Validate.bsp(newCommand, CrossPlatform.isWindows)
              case Right(c: Commands.Compile) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(c.projects, commandName, remainingArgs, commonOptions) { ps =>
                  run(newCommand.copy(projects = ps), newCommand.cliOptions)
                }
              case Right(c: Commands.Autocomplete) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Console) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(c.projects, commandName, remainingArgs, commonOptions) { ps =>
                  run(newCommand.copy(projects = ps), newCommand.cliOptions)
                }
              case Right(c: Commands.Test) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(c.projects, commandName, remainingArgs, commonOptions) { ps =>
                  run(
                    // Infer everything after '--' as if they were execution args
                    newCommand.copy(projects = ps, args = c.args ++ extraArgs),
                    newCommand.cliOptions
                  )
                }
              case Right(c: Commands.Run) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(c.projects, commandName, remainingArgs, commonOptions) { ps =>
                  run(
                    // Infer everything after '--' as if they were execution args
                    newCommand.copy(projects = ps, args = c.args ++ extraArgs),
                    newCommand.cliOptions
                  )
                }
              case Right(c: Commands.Clean) =>
                // We accept no project arguments in clean
                val potentialProjects = c.projects ++ remainingArgs
                val cliOptions = c.cliOptions.copy(common = commonOptions)
                run(c.copy(projects = potentialProjects, cliOptions = cliOptions), c.cliOptions)
              case Right(c: Commands.Projects) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Configure) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Link) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(c.projects, commandName, remainingArgs, commonOptions) { ps =>
                  run(newCommand.copy(projects = ps), newCommand.cliOptions)
                }
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
    run(action, pool, FalseCancellation)
  }

  private def run(
      action: Action,
      pool: ClientPool,
      cancel: CompletableFuture[java.lang.Boolean]
  ): ExitStatus = {
    import bloop.io.AbsolutePath
    def getConfigDir(cliOptions: CliOptions): AbsolutePath = {
      val cwd = AbsolutePath(cliOptions.common.workingDirectory)

      cliOptions.configDir
        .map(AbsolutePath.apply(_)(cwd))
        .getOrElse(cliOptions.common.workingPath.resolve(".bloop"))
    }

    val cliOptions = action match {
      case r: Run => r.command.cliOptions
      case e: Exit => CliOptions.default
      case p: Print => CliOptions.default.copy(common = p.commonOptions)
    }

    val commonOpts = cliOptions.common
    val configDirectory = getConfigDir(cliOptions)
    val debugFilter: DebugFilter = DebugFilter.toUniqueFilter(cliOptions.debug)

    // We enable verbose debugging when the user either specifies `--verbose` or `--debug FILTER`
    val isVerbose = cliOptions.verbose || debugFilter != DebugFilter.All

    val logger = BloopLogger.at(
      configDirectory.syntax,
      commonOpts.out,
      commonOpts.err,
      isVerbose,
      !(cliOptions.noColor || commonOpts.env.containsKey("NO_COLOR")),
      debugFilter
    )

    // Set the proxy settings right before loading the state of the build
    bloop.util.ProxySetup.updateProxySettings(commonOpts.env.toMap, logger)

    val isClientConnected = AtomicBoolean(false)
    pool.addListener(_ => isClientConnected.set(true))
    val client = ClientInfo.CliClientInfo("bloop-cli", () => isClientConnected.get)
    val currentState =
      State.loadActiveStateFor(configDirectory, client, pool, cliOptions.common, logger)

    val exitPromise = Promise[Unit]()
    val dir = configDirectory.underlying
    waitUntilEndOfWorld(action, cliOptions, pool, dir, logger, exitPromise, cancel) {
      val taskToInterpret = Interpreter.execute(action, currentState).map { newState =>
        // Only update the build if the command is not a BSP long-running
        // session. The BSP implementation reads and stores the state in every
        // action, so updating the build at the end of the BSP session can
        // override a newer state updated by newer clients which is unknown to BSP
        action match {
          case Run(_: Commands.ValidatedBsp, _) => ()
          case _ => State.stateCache.updateBuild(newState.copy(status = ExitStatus.Ok))
        }

        newState
      }

      acquireBuildCliLock(configDirectory, action, exitPromise, taskToInterpret, logger)
    }
  }

  private final val FalseCancellation =
    CompletableFuture.completedFuture[java.lang.Boolean](false)

  // If completed and true, client exited, otherwise client disconnected/cancelled
  private val activeCliSessions = new ConcurrentHashMap[Path, Promise[Unit]]()

  private final val CliTimeoutProperty = "bloop.cli-lock.seconds-to-timeout"
  private final val secondsTimeout: Long = {
    val default = java.lang.Integer.getInteger(CliTimeoutProperty)
    if (default == null) 60L else default.toLong
  }

  def acquireBuildCliLock(
      configDir: AbsolutePath,
      action: Action,
      syncPromise: Promise[Unit],
      taskToRun: Task[State],
      logger: Logger
  ): Task[ExitStatus] = {
    val timeoutTask = Task {
      logger.error(s"Timed out waiting on CLI lock after ${secondsTimeout}s")
      logger.info(s"  -> Tweak the default value by changing `-D$CliTimeoutProperty` in the server")
      ExitStatus.UnexpectedError
    }

    action match {
      case Exit(_) => taskToRun.map(_.status)
      // Don't synchronize on lock commands that can run concurrently on the same build for the same client
      case Run(_: Commands.About, next) => taskToRun.map(_.status)
      case Run(_: Commands.Projects, next) => taskToRun.map(_.status)
      case Run(_: Commands.Autocomplete, next) => taskToRun.map(_.status)
      case Run(_: Commands.Bsp, next) => taskToRun.map(_.status)
      case Run(_: Commands.ValidatedBsp, next) => taskToRun.map(_.status)
      case _ =>
        val currentPromise = activeCliSessions.putIfAbsent(configDir.underlying, syncPromise)
        if (currentPromise == null) taskToRun.map(_.status)
        else {
          logger.info("Waiting on external CLI client to release lock on this build...")
          Task
            .fromFuture(currentPromise.future)
            .timeoutTo(FiniteDuration(secondsTimeout, TimeUnit.SECONDS), timeoutTask)
            .flatMap { _ =>
              // Don't try to acquire lock if client waiting for it already disconnected
              if (syncPromise.isCompleted) {
                Task.now(ExitStatus.Ok)
              } else {
                acquireBuildCliLock(configDir, action, syncPromise, taskToRun, logger)
              }
            }
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
      exitOrCancelPromise: Promise[Unit],
      cancel: CompletableFuture[java.lang.Boolean] = FalseCancellation
  )(task: Task[ExitStatus]): ExitStatus = {
    val ngout = cliOptions.common.ngout
    def logElapsed(since: Long): Unit = {
      val elapsed = (System.nanoTime() - since).toDouble / 1e6
      logger.debug(s"Elapsed: $elapsed ms")(DebugFilter.All)
    }

    def releaseBuildCliLock(): Unit = {
      activeCliSessions.remove(configDirectory, exitOrCancelPromise)
      exitOrCancelPromise.trySuccess(())
      ()
    }

    // Simulate try-catch-finally with monix tasks to time the task execution
    val handle =
      Task
        .now(System.nanoTime())
        .flatMap(start => task.materialize.map(s => (s, start)))
        .map { case (state, start) => logElapsed(start); state }
        .doOnFinish(_ => Task(releaseBuildCliLock()))
        .doOnCancel(Task(releaseBuildCliLock()))
        .dematerialize
        .runAsync(ExecutionContext.scheduler)

    if (!cancel.isDone) {
      // Add support for a client to cancel bloop via Java's completable future
      import bloop.util.Java8Compat.JavaCompletableFutureUtils
      val cancelCliClient = Task
        .deferFutureAction(cancel.asScala(_))
        .map { cancel =>
          if (cancel) {
            cliOptions.common.out.println(
              s"Client in $configDirectory triggered cancellation. Cancelling tasks..."
            )
            exitOrCancelPromise.trySuccess(())
            handle.cancel()
          }
        }
        .runAsync(ExecutionContext.ioScheduler)
    }

    def handleException(t: Throwable) = {
      handle.cancel()
      if (!cancel.isDone)
        cancel.complete(false)
      if (t.getMessage != null)
        logger.error(t.getMessage)
      logger.trace(t)
      ExitStatus.UnexpectedError
    }

    try {
      // Let's cancel tasks (if supported by the underlying implementation) when clients disconnect
      pool.addListener {
        case e: CloseEvent =>
          if (!handle.isCompleted) {
            ngout.println(
              s"Client in $configDirectory disconnected with a '$e' event. Cancelling tasks..."
            )
            handle.cancel()
            exitOrCancelPromise.trySuccess(())
            if (!cancel.isDone)
              cancel.complete(false)
            ()
          }
      }

      Await.result(handle, Duration.Inf)
    } catch {
      case i: InterruptedException => handleException(i)
      case NonFatal(t) => handleException(t)
    }
  }
}
