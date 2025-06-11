package bloop

import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path
import scala.util.control.NonFatal
import bloop.cli.CliOptions
import bloop.cli.Commands
import bloop.cli.CommonOptions
import bloop.cli.ExitStatus
import bloop.cli.Validate
import bloop.data.ClientInfo.CliClientInfo
import bloop.engine._
import bloop.io.AbsolutePath
import bloop.io.Paths
import bloop.logging.BloopLogger
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.task.Task
import monix.eval.{Task => MonixTask}
import monix.eval.TaskApp
import bloop.util.JavaRuntime
import caseapp.core.help.Help
import cats.effect.ExitCode
import cats.effect.concurrent.{Deferred, Ref}
import com.martiansoftware.nailgun.NGContext
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean

import scala.concurrent.Await
import scala.concurrent.duration.Duration

class Cli
object Cli extends TaskApp {

  implicit private val filter: DebugFilter.All.type = DebugFilter.All
  def run(args: List[String]): MonixTask[ExitCode] = {
    val action = parse(args.toArray, CommonOptions.default)
    for {
      activeCliSessions <- Ref.of[MonixTask, Map[Path, List[CliSession]]](Map.empty)
      exitStatus <- run(action, NoPool, activeCliSessions)
    } yield ExitCode(exitStatus.code)
  }

  def reflectMain(
      args: Array[String],
      cwd: Path,
      in: InputStream,
      out: PrintStream,
      err: PrintStream,
      props: java.util.Properties,
      cancel: Deferred[MonixTask, Boolean],
      activeCliSessions: Ref[MonixTask, Map[Path, List[CliSession]]]
  ): MonixTask[Int] = {
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
    val exitStatus = run(cmd, NoPool, cancel, activeCliSessions)
    exitStatus.map(_.code)
  }

  def nailMain(ngContext: NGContext): Unit = {
    val env = CommonOptions.PrettyProperties.from(ngContext.getEnv)
    val nailgunOptions = CommonOptions(
      in = ngContext.in,
      out = ngContext.out,
      err = ngContext.err,
      ngout = ngContext.out,
      ngerr = ngContext.err,
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
    println(nailgunOptions.workingDirectory)

    println(nailgunOptions.workingPath)

    val handle =
      Ref
        .of[MonixTask, Map[Path, List[CliSession]]](Map.empty)
        .flatMap(run(cmd, NailgunPool(ngContext), _))
        .onErrorHandle {
          case x: java.util.concurrent.ExecutionException =>
            // print stack trace of fatal errors thrown in asynchronous code, see https://stackoverflow.com/questions/17265022/what-is-a-boxed-error-in-scala
            // the stack trace is somehow propagated all the way to the client when printing this
            x.getCause.printStackTrace(ngContext.out)
            ExitStatus.UnexpectedError.code
        }
        .runToFuture(ExecutionContext.ioScheduler)

    Await.result(handle, Duration.Inf)
    ()
  }

  val commands: Seq[String] = Commands.RawCommand.help.messages.flatMap(_._1.headOption.toSeq)
  // Getting the name from the sbt generated metadata gives us `bloop-frontend` instead.
  val beforeCommandMessages: Help[Unit] =
    caseapp.core.help
      .Help(Nil, "bloop", bloop.internal.build.BuildInfo.version, "bloop", None)
      .withOptionsDesc(s"[options] [command] [command-options]")

  private val progName: String = beforeCommandMessages.progName
  private def helpAsked: String =
    s"""${beforeCommandMessages.help}
       |Available commands: ${commands.mkString(", ")}
       |Type `$progName 'command' --help` for help on an individual command
     """.stripMargin

  private def commandHelpAsked(command: String): String = {
    // We have to do this ourselves because case-app 1.2.0 has a bug in its `ArgsName` handling.
    val messages = Commands.RawCommand.help.messagesMap(Seq(command))
    val argsName =
      if (messages.args.exists(_.name.name.startsWith("project"))) Some("project") else None
    messages.withArgsNameOption(argsName).helpMessage(beforeCommandMessages.progName, Seq(command))
  }

  private def usageAsked: String = {
    s"""${beforeCommandMessages.usage}
       |Available commands: ${commands.mkString(", ")}
       |Type `$progName 'command' --usage` for usage of an individual command
     """.stripMargin
  }

  private def aboutAsked: String = {
    val bloopName = bloop.internal.build.BuildInfo.bloopName
    val bloopVersion = bloop.internal.build.BuildInfo.version
    val scalaVersion = bloop.internal.build.BuildInfo.scalaVersion
    val zincVersion = bloop.internal.build.BuildInfo.zincVersion
    val javaVersion = JavaRuntime.version
    val javaHome = JavaRuntime.home
    val jdiStatus = {
      if (JavaRuntime.loadJavaDebugInterface.isSuccess)
        "Supports debugging user code, Java Debug Interface (JDI) is available."
      else
        "Doesn't support debugging user code, runtime doesn't implement Java Debug Interface (JDI)."
    }
    s"""$bloopName v$bloopVersion
       |
       |Using Scala v$scalaVersion and Zinc v$zincVersion
       |Running on Java ${JavaRuntime.current} v$javaVersion ($javaHome)
       |  -> $jdiStatus
       |Maintained by the Scala Center and the community.
       |""".stripMargin
  }

  private def commandUsageAsked(command: String): String =
    Commands.RawCommand.help
      .messagesMap(Seq(command))
      .usageMessage(beforeCommandMessages.progName, Seq(command))

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
    import caseapp.core.help.WithHelp
    Commands.RawCommand.parser.withHelp.detailedParse(args)(CliOptions.parser.withHelp) match {
      case Left(err) => printErrorAndExit(err.message, commonOptions)
      case Right((WithHelp(_, help @ true, _), _, _)) =>
        Print(helpAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(usage @ true, _, _), _, _)) =>
        Print(usageAsked, commonOptions, Exit(ExitStatus.Ok))
      case Right((WithHelp(_, _, userOptions), _, commandOpt)) =>
        val newAction = commandOpt map {
          case Left(err) => printErrorAndExit(err.message, commonOptions)
          case Right((commandName, WithHelp(_, help @ true, _), _)) =>
            Print(commandHelpAsked(commandName.mkString(" ")), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(usage @ true, _, _), _)) =>
            Print(commandUsageAsked(commandName.mkString(" ")), commonOptions, Exit(ExitStatus.Ok))
          case Right((commandName, WithHelp(_, _, command), remainingArgs)) =>
            // Override common options depending who's the caller of parse (whether nailgun or main)
            def run(command: Commands.RawCommand, cliOptions: CliOptions): Run = {
              if (!cliOptions.version) Run(command, Exit(ExitStatus.Ok))
              else Run(Commands.About(cliOptions), Run(command, Exit(ExitStatus.Ok)))
            }

            command match {
              case Left(err) => printErrorAndExit(err.message, commonOptions)
              case Right(_: Commands.Help) =>
                Print(helpAsked, commonOptions, Exit(ExitStatus.Ok))
              case Right(_: Commands.About) =>
                Print(aboutAsked, commonOptions, Exit(ExitStatus.Ok))
              case Right(c: Commands.Bsp) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                Validate.bsp(newCommand)
              case Right(c: Commands.Compile) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(
                  c.projects,
                  commandName.mkString(" "),
                  remainingArgs.all,
                  commonOptions
                ) { ps =>
                  run(newCommand.copy(projects = ps), newCommand.cliOptions)
                }
              case Right(c: Commands.Autocomplete) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                run(newCommand, newCommand.cliOptions)
              case Right(c: Commands.Console) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(
                  c.projects,
                  commandName.mkString(" "),
                  remainingArgs.remaining,
                  commonOptions
                ) { ps =>
                  run(
                    // Infer everything after '--' as if they were execution args
                    newCommand.copy(projects = ps, args = c.args ++ remainingArgs.unparsed),
                    newCommand.cliOptions
                  )
                }
              case Right(c: Commands.Test) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(
                  c.projects,
                  commandName.mkString(" "),
                  remainingArgs.remaining,
                  commonOptions
                ) { ps =>
                  run(
                    // Infer everything after '--' as if they were execution args
                    newCommand.copy(projects = ps, args = c.args ++ remainingArgs.unparsed),
                    newCommand.cliOptions
                  )
                }
              case Right(c: Commands.Run) =>
                val newCommand = c.copy(cliOptions = c.cliOptions.copy(common = commonOptions))
                withNonEmptyProjects(
                  c.projects,
                  commandName.mkString(" "),
                  remainingArgs.remaining,
                  commonOptions
                ) { ps =>
                  run(
                    // Infer everything after '--' as if they were execution args
                    newCommand.copy(projects = ps, args = c.args ++ remainingArgs.unparsed),
                    newCommand.cliOptions
                  )
                }
              case Right(c: Commands.Clean) =>
                // We accept no project arguments in clean
                val potentialProjects = c.projects ++ remainingArgs.remaining
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
                withNonEmptyProjects(
                  c.projects,
                  commandName.mkString(" "),
                  remainingArgs.remaining,
                  commonOptions
                ) { ps =>
                  run(newCommand.copy(projects = ps), newCommand.cliOptions)
                }
            }
        }
        newAction.getOrElse {
          userOptions match {
            case Left(err) => printErrorAndExit(err.message, commonOptions)
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

  def run(
      action: Action,
      pool: ClientPool,
      activeCliSessions: Ref[MonixTask, Map[Path, List[CliSession]]]
  ): MonixTask[ExitStatus] = {
    for {
      baseCancellation <- Deferred[MonixTask, Boolean]
      _ <- baseCancellation.complete(false)
      result <- run(action, pool, baseCancellation, activeCliSessions)
    } yield result
  }

  // Attempt to load JDI when we initialize the CLI class
  private val _ = JavaRuntime.loadJavaDebugInterface
  private def run(
      action: Action,
      pool: ClientPool,
      cancel: Deferred[MonixTask, Boolean],
      activeCliSessions: Ref[MonixTask, Map[Path, List[CliSession]]]
  ): MonixTask[ExitStatus] = {
    import bloop.io.AbsolutePath
    def getConfigDir(cliOptions: CliOptions): AbsolutePath = {
      val cwd = AbsolutePath(cliOptions.common.workingDirectory)

      cliOptions.configDir
        .map(AbsolutePath.apply(_)(cwd))
        .getOrElse(cliOptions.common.workingPath.resolve(".bloop"))
    }

    val cliOptions = action match {
      case r: Run => r.command.cliOptions
      case _: Exit => CliOptions.default
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
    action match {
      case Print(msg, _, Exit(exitStatus)) =>
        logger.info(msg)
        MonixTask.now(exitStatus)
      case _ =>
        runWithState(
          action,
          pool,
          cancel,
          activeCliSessions,
          configDirectory,
          cliOptions,
          commonOpts,
          logger
        )
    }
  }

  private def runWithState(
      action: Action,
      pool: ClientPool,
      cancel: Deferred[MonixTask, Boolean],
      activeCliSessions: Ref[MonixTask, Map[Path, List[CliSession]]],
      configDirectory: AbsolutePath,
      cliOptions: CliOptions,
      commonOpts: CommonOptions,
      logger: Logger
  ): MonixTask[ExitStatus] = {
    // Set the proxy settings right before loading the state of the build
    bloop.util.ProxySetup.updateProxySettings(commonOpts.env.toMap, logger)

    val configDir = configDirectory.underlying
    waitUntilEndOfWorld(cliOptions, pool, configDir, logger, cancel) {
      val taskToInterpret = { (cli: CliClientInfo) =>
        val state = State.loadActiveStateFor(configDirectory, cli, pool, cliOptions.common, logger)
        val interpret = Interpreter.execute(action, state).map { newState =>
          action match {
            case Run(_: Commands.ValidatedBsp, _) =>
              () // Ignore, BSP services auto-update the build
            case _ => State.stateCache.updateBuild(newState.copy(status = ExitStatus.Ok))
          }

          newState
        }
        MonixTask.defer(interpret.toMonixTask(ExecutionContext.scheduler))
      }

      val session = runTaskWithCliClient(
        configDirectory,
        action,
        taskToInterpret,
        activeCliSessions,
        pool
      )
      val exitSession = MonixTask.defer {
        session.flatMap { session =>
          cleanUpNonStableCliDirectories(session.client, logger).flatMap { _ =>
            activeCliSessions.update(_ - configDirectory.underlying)
          }
        }
      }

      session
        .flatMap(_.task)
        .guarantee(exitSession)
    }
  }

  case class CliSession(client: CliClientInfo, task: MonixTask[ExitStatus])
  def runTaskWithCliClient(
      configDir: AbsolutePath,
      action: Action,
      processCliTask: CliClientInfo => MonixTask[State],
      activeCliSessions: Ref[MonixTask, Map[Path, List[CliSession]]],
      pool: ClientPool
  ): MonixTask[CliSession] = {
    val isClientConnected = AtomicBoolean(true)
    pool.addListener(_ => isClientConnected.set(false))
    val defaultClient = CliClientInfo(useStableCliDirs = true, () => isClientConnected.get)

    def sessionFor(client: CliClientInfo): CliSession = {
      val cliTask = processCliTask(client).map(_.status)
      CliSession(client, cliTask)
    }

    action match {
      case Exit(_) => MonixTask.now(sessionFor(defaultClient))
      // Don't synchronize on commands that don't use compilation products and can run concurrently
      case Run(_: Commands.About, _) => MonixTask.now(sessionFor(defaultClient))
      case Run(_: Commands.Projects, _) => MonixTask.now(sessionFor(defaultClient))
      case Run(_: Commands.Autocomplete, _) => MonixTask.now(sessionFor(defaultClient))
      case Run(_: Commands.Bsp, _) => MonixTask.now(sessionFor(defaultClient))
      case Run(_: Commands.ValidatedBsp, _) => MonixTask.now(sessionFor(defaultClient))
      case a @ _ =>
        activeCliSessions
          .modify { sessionsMap =>
            sessionsMap.get(configDir.underlying) match {
              case Some(sessions) =>
                val newClient = CliClientInfo(useStableCliDirs = false, () => isClientConnected.get)
                val newClientSession = sessionFor(newClient)
                (
                  sessionsMap.updated(configDir.underlying, newClientSession :: sessions),
                  newClientSession
                )
              case None =>
                val newSession = sessionFor(defaultClient)
                (
                  sessionsMap.updated(configDir.underlying, List(newSession)),
                  newSession
                )
            }
          }
    }
  }

  def cleanUpNonStableCliDirectories(
      client: CliClientInfo,
      logger: Logger
  ): MonixTask[Unit] = {
    if (client.useStableCliDirs) MonixTask.unit
    else {
      logger.debug(
        s"Cleaning up non-stable CLI directories ${client.getCreatedCliDirectories.mkString(",")}"
      )
      val deleteTasks = client.getCreatedCliDirectories.map { freshDir =>
        if (!freshDir.exists) MonixTask.unit
        else {
          MonixTask.eval(Paths.delete(freshDir)).asyncBoundary
        }
      }

      val groups = deleteTasks
        .grouped(4)
        .map(group => MonixTask.parSequenceUnordered(group).map(_ => ()))
        .toList

      MonixTask
        .sequence(groups)
        .map(_ => ())
        .executeOn(ExecutionContext.ioScheduler)
    }
  }

  private[bloop] def waitUntilEndOfWorld(
      cliOptions: CliOptions,
      pool: ClientPool,
      configDirectory: Path,
      logger: Logger
  )(task: Task[ExitStatus])(implicit s: Scheduler): ExitStatus = {
    val handler =
      for {
        cancel <- Deferred[MonixTask, Boolean]
        exitStatus <- waitUntilEndOfWorld(cliOptions, pool, configDirectory, logger, cancel)(
          task.toMonixTask
        )
      } yield exitStatus
    Await.result(
      handler.runToFuture(ExecutionContext.ioScheduler),
      Duration.Inf
    )
  }

  private[bloop] def waitUntilEndOfWorld(
      cliOptions: CliOptions,
      pool: ClientPool,
      configDirectory: Path,
      logger: Logger,
      cancel: Deferred[MonixTask, Boolean]
  )(task: MonixTask[ExitStatus]): MonixTask[ExitStatus] = {

    val ngout = cliOptions.common.ngout
    def logElapsed(since: Long): Unit = {
      val elapsed = (System.nanoTime() - since).toDouble / 1e6
      logger.debug(s"Elapsed: $elapsed ms")(DebugFilter.All)
    }

    // Simulate try-catch-finally with monix tasks to time the task execution
    val handle: MonixTask[ExitStatus] =
      for {
        start <- MonixTask.now(System.nanoTime())
        tryState <- task.materialize
        _ = logElapsed(start)
        state <- MonixTask.fromTry(tryState)
      } yield state

    def waitForCanceled: MonixTask[ExitStatus] =
      for {
        isCanceled <- cancel.get
        status <-
          if (isCanceled)
            MonixTask
              .now {
                cliOptions.common.out.println(
                  s"Client in $configDirectory triggered cancellation. Cancelling tasks..."
                )
              }
              .map(_ => ExitStatus.UnexpectedError)
          else MonixTask.now(ExitStatus.Ok)
      } yield status

    def handleException(t: Throwable, completed: Boolean): MonixTask[ExitStatus] =
      for {
        _ <- if (completed) cancel.complete(completed) else MonixTask.unit
      } yield {
        logger.error(s"Caught $t")
        logger.trace(t)
        ExitStatus.UnexpectedError
      }
    // Let's cancel tasks (if supported by the underlying implementation) when clients disconnect
    def registerListener(
        cancelSignal: Deferred[MonixTask, Boolean]
    )(implicit s: Scheduler): MonixTask[Unit] = {
      MonixTask {
        pool.addListener { e: CloseEvent =>
          MonixTask
            .defer {
              cancelSignal
                .complete(true)
                .attempt
                .map {
                  case Right(()) =>
                    ngout.println(
                      s"Client in $configDirectory disconnected with a '$e' event. Cancelling tasks..."
                    )
                  case Left(_) =>
                    ngout.println(
                      s"Client in $configDirectory disconnected with a '$e' event. Cancelling tasks..."
                    )
                }
            }
            .uncancelable // TODO: do we need it?
            .runAsyncAndForget
        }
      }
    }

    MonixTask
      .racePair(
        registerListener(cancel)(Scheduler.singleThread("cancel-signal")) *> waitForCanceled,
        handle
      )
      .flatMap {
        case Left((result, fiber)) =>
          if (result.isOk) fiber.join else fiber.cancel *> MonixTask.now(result)
        case Right((fiber, _)) => fiber.join
      }
      .onErrorHandleWith {
        case i: InterruptedException => handleException(i, completed = true)
      }
      .onErrorRecoverWith {
        case NonFatal(t) => handleException(t, completed = false)
      }
  }
}
