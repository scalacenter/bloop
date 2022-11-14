package bloop

import java.io.InputStream
import java.io.PrintStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

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
import bloop.util.CrossPlatform
import bloop.util.JavaRuntime

import caseapp.core.help.Help
import com.martiansoftware.nailgun.NGContext
import monix.execution.atomic.AtomicBoolean

class Cli
object Cli {

  implicit private val filter: DebugFilter.All.type = DebugFilter.All
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

    try {
      val exitStatus = run(cmd, NailgunPool(ngContext))
      ngContext.exit(exitStatus.code)
    } catch {
      case x: java.util.concurrent.ExecutionException =>
        // print stack trace of fatal errors thrown in asynchronous code, see https://stackoverflow.com/questions/17265022/what-is-a-boxed-error-in-scala
        // the stack trace is somehow propagated all the way to the client when printing this
        x.getCause.printStackTrace(ngContext.out)
        ngContext.exit(ExitStatus.UnexpectedError.code)
    }
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
                Validate.bsp(newCommand, CrossPlatform.isWindows)
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
    waitUntilEndOfWorld(cliOptions, pool, configDir, logger, cancel) {
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
        cleanUpNonStableCliDirectories(session.client)
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
      case Run(_: Commands.About, _) => defaultClientSession
      case Run(_: Commands.Projects, _) => defaultClientSession
      case Run(_: Commands.Autocomplete, _) => defaultClientSession
      case Run(_: Commands.Bsp, _) => defaultClientSession
      case Run(_: Commands.ValidatedBsp, _) => defaultClientSession
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

  def cleanUpNonStableCliDirectories(
      client: CliClientInfo
  ): Task[Unit] = {
    if (client.useStableCliDirs) Task.unit
    else {
      val deleteTasks = client.getCreatedCliDirectories.map { freshDir =>
        if (!freshDir.exists) Task.unit
        else {
          Task.eval(Paths.delete(freshDir)).asyncBoundary
        }
      }

      val groups = deleteTasks
        .grouped(4)
        .map(group => Task.gatherUnordered(group).map(_ => ()))
        .toList

      Task
        .sequence(groups)
        .map(_ => ())
        .executeOn(ExecutionContext.ioScheduler)
    }
  }

  import scala.concurrent.Await
  import scala.concurrent.duration.Duration
  private[bloop] def waitUntilEndOfWorld(
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
      Task
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
