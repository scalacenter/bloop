package bloop

import java.io.{InputStream, PrintStream}
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

import bloop.bsp.BspServer
import bloop.io.AbsolutePath
import bloop.util.{CrossPlatform, JavaRuntime}
import bloop.cli.{CliOptions, CliParsers, Commands, CommonOptions, ExitStatus, Validate}
import bloop.engine._
import bloop.engine.tasks.Tasks
import bloop.logging.{BloopLogger, DebugFilter, Logger}
import bloop.data.ClientInfo.CliClientInfo

import caseapp.core.{DefaultBaseCommand, Messages}
import com.martiansoftware.nailgun.NGContext
import _root_.monix.eval.Task

import scala.concurrent.Promise
import scala.util.control.NonFatal
import caseapp.core.CommandsMessages
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.TimeUnit
import monix.execution.atomic.AtomicBoolean
import bloop.io.Paths
import scala.util.Success
import scala.util.Failure
import caseapp.core.help.WithHelp

class Cli
object Cli {

  implicit private val filter = DebugFilter.All
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

  private def aboutAsked: String = {
    val bloopName = bloop.internal.build.BuildInfo.bloopName
    val bloopVersion = bloop.internal.build.BuildInfo.version
    val scalaVersion = bloop.internal.build.BuildInfo.scalaVersion
    val zincVersion = bloop.internal.build.BuildInfo.zincVersion
    val developers = bloop.internal.build.BuildInfo.developers.mkString(", ")
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
       |Maintained by the Scala Center ($developers)
       |""".stripMargin
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
    // import caseapp.core.WithHelp
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
              case Right(_: Commands.About) =>
                Print(aboutAsked, commonOptions, Exit(ExitStatus.Ok))
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
                  run(
                    // Infer everything after '--' as if they were execution args
                    newCommand.copy(projects = ps, args = c.args ++ extraArgs),
                    newCommand.cliOptions
                  )
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

  // Attempt to load JDI when we initialize the CLI class
  private val _ = JavaRuntime.loadJavaDebugInterface
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

    action match {
      case Print(msg, commonOptions, Exit(exitStatus)) =>
        logger.info(msg)
        exitStatus
      case _ =>
        runWithState(action, pool, cancel, configDirectory, cliOptions, commonOpts, logger)
    }
  }

  private def runWithState(
      action: Action,
      pool: ClientPool,
      cancel: CompletableFuture[java.lang.Boolean],
      configDirectory: AbsolutePath,
      cliOptions: CliOptions,
      commonOpts: CommonOptions,
      logger: Logger
  ): ExitStatus = {

    // Set the proxy settings right before loading the state of the build
    bloop.util.ProxySetup.updateProxySettings(commonOpts.env.toMap, logger)

    val configDir = configDirectory.underlying
    waitUntilEndOfWorld(action, cliOptions, pool, configDir, logger, cancel) {
      val taskToInterpret = { (cli: CliClientInfo) =>
        val state = State.loadActiveStateFor(configDirectory, cli, pool, cliOptions.common, logger)
        Interpreter.execute(action, state).map { newState =>
          action match {
            case Run(_: Commands.ValidatedBsp, _) =>
              () // Ignore, BSP services auto-update the build
            case _ => State.stateCache.updateBuild(newState.copy(status = ExitStatus.Ok))
          }

          newState
        }
      }

      val session = runTaskWithCliClient(configDirectory, action, taskToInterpret, pool, logger)
      val exitSession = Task.defer {
        handleCliClientExit(configDir, session, logger)
        cleanUpNonStableCliDirectories(configDir, session.client, cliOptions.common.ngout)
      }

      session.task
        .doOnCancel(exitSession)
        .doOnFinish(_ => exitSession)
    }
  }

  private final val FalseCancellation =
    CompletableFuture.completedFuture[java.lang.Boolean](false)

  private val activeCliSessions = new ConcurrentHashMap[Path, List[CliSession]]()

  case class CliSession(client: CliClientInfo, task: Task[ExitStatus])
  def runTaskWithCliClient(
      configDir: AbsolutePath,
      action: Action,
      processCliTask: CliClientInfo => Task[State],
      pool: ClientPool,
      logger: Logger
  ): CliSession = {
    val isClientConnected = AtomicBoolean(true)
    pool.addListener(_ => isClientConnected.set(false))
    val defaultClient = CliClientInfo(useStableCliDirs = true, () => isClientConnected.get)

    def sessionFor(client: CliClientInfo): CliSession = {
      val cliTask = processCliTask(client).map(_.status)
      CliSession(client, cliTask)
    }

    val defaultClientSession = sessionFor(defaultClient)
    action match {
      case Exit(_) => defaultClientSession
      // Don't synchronize on commands that don't use compilation products and can run concurrently
      case Run(_: Commands.About, next) => defaultClientSession
      case Run(_: Commands.Projects, next) => defaultClientSession
      case Run(_: Commands.Autocomplete, next) => defaultClientSession
      case Run(_: Commands.Bsp, next) => defaultClientSession
      case Run(_: Commands.ValidatedBsp, next) => defaultClientSession
      case _ =>
        val activeSessions = activeCliSessions.compute(
          configDir.underlying,
          (_: Path, sessions: List[CliSession]) => {
            if (sessions == null || sessions.isEmpty) List(defaultClientSession)
            else {
              logger.debug("Detected connected cli clients, starting CLI with unique dirs...")
              val newClient = CliClientInfo(useStableCliDirs = false, () => isClientConnected.get)
              val newClientSession = sessionFor(newClient)
              newClientSession :: sessions
            }
          }
        )

        activeSessions.head
    }
  }

  def handleCliClientExit(configDir: Path, session: CliSession, logger: Logger): Unit = {
    var sessionsIsNull = false
    val result = activeCliSessions.compute(
      configDir,
      (_: Path, sessions: List[CliSession]) => {
        if (sessions != null) {
          sessions.filterNot(_ == session)
        } else {
          sessionsIsNull = false
          Nil
        }
      }
    )

    if (sessionsIsNull) {
      logger.debug(s"Unexpected counter for $configDir is null, report upstream!")
    }

    ()
  }

  def cleanUpNonStableCliDirectories(
      configDir: Path,
      client: CliClientInfo,
      out: PrintStream
  ): Task[Unit] = {
    if (client.useStableCliDirs) Task.unit
    else {
      val deleteTasks = client.getCreatedCliDirectories.map { freshDir =>
        if (!freshDir.exists) Task.unit
        else {
          out.println(s"Preparing to delete dir ${freshDir}")
          Task.eval(Paths.delete(freshDir)).executeWithFork
        }
      }

      val groups = deleteTasks
        .grouped(4)
        .map(group => Task.gatherUnordered(group).map(_ => ()))

      Task
        .sequence(groups)
        .map(_ => ())
        .executeOn(ExecutionContext.ioScheduler)
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
      cancel: CompletableFuture[java.lang.Boolean] = FalseCancellation
  )(task: Task[ExitStatus]): ExitStatus = {
    val ngout = cliOptions.common.ngout
    def logElapsed(since: Long): Unit = {
      val elapsed = (System.nanoTime() - since).toDouble / 1e6
      logger.debug(s"Elapsed: $elapsed ms")(DebugFilter.All)
    }

    // Simulate try-catch-finally with monix tasks to time the task execution
    val handle =
      Task
        .now(System.nanoTime())
        .flatMap(start => task.materialize.map(s => (s, start)))
        .map { case (state, start) => logElapsed(start); state }
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
