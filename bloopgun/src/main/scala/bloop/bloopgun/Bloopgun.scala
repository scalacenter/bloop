package bloop.bloopgun

import bloop.bloopgun.core.Shell
import bloop.bloopgun.core.DependencyResolution
import bloop.bloopgun.util.Environment
import bloop.bloopgun.util.Feedback
import bloop.bloopgun.core.AvailableAtPath
import bloop.bloopgun.core.AvailableWithCommand
import bloop.bloopgun.core.ListeningAndAvailableAt
import bloop.bloopgun.core.ServerStatus
import bloop.bloopgun.core.ResolvedAt

import java.io.PrintStream
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.net.ConnectException
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.Path
import java.nio.charset.StandardCharsets

import snailgun.TcpClient
import snailgun.protocol.Streams
import snailgun.logging.SnailgunLogger

import scala.collection.mutable

import scopt.OParser

import scala.sys.process.ProcessIO
import java.lang.ProcessBuilder.Redirect
import scala.util.Try
import java.net.URLDecoder
import java.net.URLClassLoader
import bloop.bloopgun.core.Shell.StatusCommand
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.Promise
import snailgun.logging.Logger
import bloop.bloopgun.core.LocatedServer
import java.io.InputStreamReader
import java.io.BufferedReader

/**
 *
 * The main library entrypoint for bloopgun, the Bloop binary CLI.
 *
 * It uses Nailgun to communicate to the Bloop server and has support for:
 *
 *   1. Prints Bloop-specific feedback to the user to improve the CLI UX.
 *      * Print custom error if user types `bloop repl`.
 *      * Recomend using `bloop --nailgun-help` if `help` set to print CLI help.
 *   2. Starts the server if already not running or if `bloop server` is used.
 *   3. Invokes Ammonite if `bloop console` is used and repl kind is default or
 *      matches Ammonite.
 *
 * For the moment, this client doesn't do any kind of version handling so if
 * the client and the server have serious incompatibilities the communication
 * could crash. To avoid that users can forcefully exit the server and let the
 * client start a session. Exiting requires the intervention of the user
 * because it can affect already connected clients to the server instance.
 */
class BloopgunCli(
    bloopVersion: String,
    in: InputStream,
    out: PrintStream,
    err: PrintStream,
    shell: Shell
) {
  def run(args: Array[String]): Int = {
    var setServer: Boolean = false
    var setPort: Boolean = false
    var parsedServerOptionFlag: Option[String] = None
    var additionalCmdArgs: List[String] = Nil

    val cliParser = {
      val builder = OParser.builder[BloopgunParams]
      val nailgunServerOpt = builder
        .opt[String]("nailgun-server")
        .action((server, params) => { setServer = true; params.copy(nailgunServer = server) })
        .text("Specify the host name of the target Bloop server")
      val nailgunPortOpt = builder
        .opt[Int]("nailgun-port")
        .action((port, params) => { setPort = true; params.copy(nailgunPort = port) })
        .text("Specify the port of the target Bloop server")
      val helpOpt = builder
        .opt[Unit]('h', "help")
        .action((_, params) => params.copy(help = true))
        .text("Print help of the Bloop server")
      val nailgunShowVersionOpt = builder
        .opt[Unit]("nailgun-showversion")
        .action((_, params) => params.copy(nailgunShowVersion = true))
        .text("Print Bloopgun version before running command")
      val nailgunHelpOpt = builder
        .help("nailgun-help")
        .text("Print Bloopgun's help client information")
      val nailgunVerboseOpt = builder
        .opt[Unit]("nailgun-verbose")
        .action((_, params) => params.copy(verbose = true))
        .text("Enable verbosity of Bloopgun's logs")
      val cmdOpt = builder
        .arg[String]("<cmd>...")
        .optional()
        .unbounded()
        .action { (arg, params) =>
          additionalCmdArgs = additionalCmdArgs ++ List(arg)
          params
        }
        .text("The command and arguments for the Bloop server")

      // Here for backwards bincompat reasons with previous Python-based bloop client
      val serverCmd = builder
        .cmd("server")
        .action((_, params) => params.copy(server = true))
        .children(
          builder
            .arg[Int]("<nailgun-port>")
            .optional()
            .maxOccurs(1)
            .action {
              case (arg, params) =>
                params.copy(serverConfig = params.serverConfig.copy(port = Some(arg)))
            },
          builder
            .opt[String]("server-location")
            .action {
              case (arg, params) =>
                val path = Some(Paths.get(arg))
                params.copy(serverConfig = params.serverConfig.copy(serverLocation = path))
            },
          builder
            .opt[Unit]("fire-and-forget")
            .action {
              case (_, params) =>
                params.copy(serverConfig = params.serverConfig.copy(fireAndForget = true))
            },
          builder
            .arg[String]("<server-args>...")
            .optional()
            .unbounded()
            .action(
              (arg, params) =>
                params.copy(
                  serverConfig = params.serverConfig.copy(serverArgs = params.args ++ List(arg))
                )
            )
            .text("The command and arguments for the Bloop server")
        )
      OParser
        .sequence(
          builder.programName("bloopgun"),
          builder.head("bloopgun", Defaults.Version),
          builder.note(
            s"""Bloopgun is a bloop CLI client that communicates with the Bloop server via Nailgun.
               |""".stripMargin
          ),
          nailgunServerOpt,
          nailgunPortOpt,
          helpOpt,
          nailgunHelpOpt,
          nailgunShowVersionOpt,
          nailgunVerboseOpt,
          cmdOpt,
          serverCmd
        )
    }

    import scopt.{OParserSetup, DefaultOParserSetup}
    val setup: OParserSetup = new DefaultOParserSetup {
      override def errorOnUnknownArgument: Boolean = false
      override def reportWarning(msg: String): Unit = {
        if (msg.startsWith("Unknown option ")) {
          additionalCmdArgs = additionalCmdArgs ++ List(msg.stripPrefix("Unknown option "))
        } else {
          err.println(msg)
        }
      }
    }

    val (cliArgsToParse, extraArgsForServer) =
      if (args.contains("--")) args.span(_ == "--") else (args, Array.empty[String])
    OParser.parse(cliParser, cliArgsToParse, BloopgunParams(), setup) match {
      case None => 1
      case Some(params0) =>
        val params = params0.copy(args = additionalCmdArgs ++ extraArgsForServer)
        val logger = new SnailgunLogger("log", out, isVerbose = params.verbose)
        if (params.nailgunShowVersion)
          logger.info(s"Nailgun protocol v${Defaults.Version}")

        if (params.server) {
          val config = params.serverConfig
          shell.connectToBloopPort(config, logger) match {
            case true => logger.info(s"Server is already running at $config, exiting!"); 0
            case _ if config.fireAndForget =>
              fireCommand("about", Array.empty, params, config, logger)
            case _ =>
              // Fire server and wait until it exits, this is the default `bloop server` mode
              fireServer(FireAndWaitForExit, params, config, bloopVersion, logger) match {
                case Some((cmd, status)) =>
                  logger.info(s"Command '$cmd' finished with ${status.code}, bye!"); 0
                case None =>
                  logger.error("Failed to locate server, aborting start of server!"); 1
              }
          }
        } else {
          val config = ServerConfig(
            if (setServer) Some(params.nailgunServer)
            else Defaults.env.get("BLOOP_SERVER"),
            if (setPort) Some(params.nailgunPort)
            else Defaults.env.get("BLOOP_PORT").map(_.toInt)
          )

          params.args match {
            case Nil if params.help => fireCommand("help", Array.empty, params, config, logger)
            case Nil => logger.error("Missing CLI command for Bloop server!"); 1
            case cmd :: cmdArgs => fireCommand(cmd, cmdArgs.toArray, params, config, logger)
          }
        }
    }
  }

  // Disable interactivity of nailgun, let's just assume all clients are not interactive
  private val isInteractive = false
  private def fireCommand(
      cmd: String,
      initialCmdArgs: Array[String],
      params: BloopgunParams,
      config: ServerConfig,
      logger: SnailgunLogger
  ): Int = {
    var consoleCmdOutFile: Path = null
    val cmdArgs = {
      if (cmd != "console") initialCmdArgs
      else {
        val outFileIndex = initialCmdArgs.indexOf("--out-file")
        if (outFileIndex >= 0) {
          if (outFileIndex + 1 < initialCmdArgs.length) {
            consoleCmdOutFile = Paths.get(initialCmdArgs(outFileIndex + 1))
          }
          initialCmdArgs
        } else {
          consoleCmdOutFile = Files.createTempFile("ammonite-cmd", "out")
          initialCmdArgs ++ Array("--out-file", consoleCmdOutFile.toAbsolutePath.toString)
        }
      }
    }

    import Defaults.env
    import Environment.cwd
    val streams = Streams(in, out, err)
    val client = TcpClient(config.userOrDefaultHost, config.userOrDefaultPort)
    val noCancel = new AtomicBoolean(false)

    def executeCmd(client: TcpClient) = {
      val code = client.run(cmd, cmdArgs, cwd, env, streams, logger, noCancel, isInteractive)
      logger.debug(s"Return code is $code")
      runAfterCommand(cmd, cmdArgs, consoleCmdOutFile, code, logger)
      code
    }

    try executeCmd(client)
    catch {
      case _: ConnectException =>
        cmd match {
          case "ng-stop" | "exit" =>
            logger.info(s"No server running at ${config}, skipping 'exit' or 'ng-stop' command!")
            0

          case _ =>
            // Attempt to start server here, move launcher logic
            logger.info(s"No server running at ${config}, let's fire one...")
            fireServer(FireInBackground, params, config, bloopVersion, logger) match {
              case Some(true) => executeCmd(client)
              case _ => logger.error(Feedback.serverCouldNotBeStarted(config)); 1
            }
        }

    } finally {
      if (consoleCmdOutFile != null) {
        Files.deleteIfExists(consoleCmdOutFile)
      }
      ()
    }
  }

  sealed trait FireMode {
    type Out

    def fire(
        found: LocatedServer,
        config: ServerConfig,
        logger: SnailgunLogger
    ): Out
  }

  case object FireAndWaitForExit extends FireMode {
    type Out = ExitServerStatus
    def fire(
        found: LocatedServer,
        config: ServerConfig,
        logger: SnailgunLogger
    ): Out = {
      startServer(found, config, true, logger)
    }
  }

  case object FireInBackground extends FireMode {
    type Out = Boolean
    def fire(
        found: LocatedServer,
        config: ServerConfig,
        logger: SnailgunLogger
    ): Out = {
      val exitPromise = Promise[ExitServerStatus]()
      val serverThread = shell.startThread("bloop-server-background", true) {
        exitPromise.success(startServer(found, config, false, logger))
      }

      val port = config.userOrDefaultPort
      var isConnected: Boolean = false

      /*
       * Retry connecting to the server for a bunch of times until we get some
       * response the server is up and running. The server can be slow to start
       * up, particularly in Windows systems where antivirus usually have a
       * high tax on application startup times. This operation usually takes
       * around a second on Linux and Unix systems.
       */
      while (!exitPromise.isCompleted && !isConnected) {
        val waitMs = 125.toLong
        Thread.sleep(waitMs)
        logger.debug(s"Sleeping for ${waitMs}ms until we connect to server port $port")
        isConnected = shell.connectToBloopPort(config, logger)
      }

      // Either listening exists or status promise is completed
      if (isConnected) true
      else {
        exitPromise.future.value match {
          case Some(scala.util.Success((runCmd, status))) =>
            val cmd = runCmd.mkString(" ")
            logger.error(s"Command '$cmd' finished with ${status.code}: '${status.output}'")
          case Some(scala.util.Failure(t)) =>
            logger.error(s"Unexpected exception thrown by thread starting server: '$t'")
          case unexpected =>
            logger.error(s"Unexpected error when starting server: $unexpected")
        }

        // Required to protect ourselves from other clients racing to start the server
        logger.info("Attempt connection a last time before giving up...")
        Thread.sleep(500.toLong)
        isConnected = shell.connectToBloopPort(config, logger)
        isConnected
      }
    }
  }

  def fireServer(
      mode: FireMode,
      params: BloopgunParams,
      config: ServerConfig,
      bloopVersion: String,
      logger: SnailgunLogger
  ): Option[mode.Out] = {
    ServerStatus
      .findServerToRun(bloopVersion, config.serverLocation, shell, logger)
      .orElse(ServerStatus.resolveServer(bloopVersion, logger))
      .map(mode.fire(_, config, logger))
  }

  // Reused across the two different ways we can run a server
  type ExitServerStatus = (List[String], StatusCommand)

  /**
   * Start a server with the executable present in `binary`.
   *
   * This operation can take a while in some operating systems (most notably
   * Windows, where antivirus programs slow down the execution of startup).
   * Therefore, call sites usually start the server in background and wait on
   * the value of `exitPromise`. The promise will not be completed so long as
   * the server is running and at the end it will contain either a success or
   * failure.
   *
   * @param serverToRun The server that we want to run.
   * @param config The configuration for the server we want to launch.
   * @param redirectOutErr Whether we should forward logs from the system process
   *        to the inherited streams. This is typically used when `bloop server` runs.
   */
  def startServer(
      serverToRun: LocatedServer,
      config: ServerConfig,
      redirectOutErr: Boolean,
      logger: Logger
  ): ExitServerStatus = {
    // Always keep a server running in the background by making it a daemon thread
    val serverArgs = {
      if (!config.serverArgs.isEmpty) config.serverArgs
      else {
        (config.host, config.port) match {
          case (Some(host), Some(port)) => List(host, port.toString)
          case (None, Some(port)) => List(port.toString)
          case (None, None) => Nil
          case (Some(host), None) =>
            logger.warn(Feedback.unexpectedServerArgsSyntax(host))
            Nil
        }
      }
    }

    def cmdWithArgs(
        found: LocatedServer,
        extraJvmOpts: List[String]
    ): (List[String], Boolean) = {
      var usedExtraJvmOpts = false
      def finalJvmOpts(jvmOpts: List[String]): List[String] = {
        if (extraJvmOpts.forall(opt => jvmOpts.contains(opt))) jvmOpts
        else {
          usedExtraJvmOpts = true
          jvmOpts ++ extraJvmOpts
        }
      }

      def deriveCmdForPath(path: Path): List[String] = {
        val fullPath = path.toAbsolutePath().toString()
        if (Files.isExecutable(path.toRealPath())) {
          val jargs = finalJvmOpts(Nil).map(arg => s"-J$arg")
          val cmd = fullPath :: (serverArgs ++ jargs)
          shell.deriveCommandForPlatform(cmd, attachTerminal = false)
        } else {
          val jvmOpts = Environment.detectJvmOptionsForServer(found, serverArgs, logger)
          List("java") ++ finalJvmOpts(jvmOpts) ++ List("-jar", fullPath) ++ serverArgs
        }
      }

      found match {
        case AvailableAtPath(path) => deriveCmdForPath(path) -> usedExtraJvmOpts
        case AvailableWithCommand(cmd) =>
          val jargs = finalJvmOpts(Nil).map(arg => s"-J$arg")
          (cmd ++ serverArgs ++ jargs) -> usedExtraJvmOpts
        case ResolvedAt(classpath) =>
          val delimiter = java.io.File.pathSeparator
          val jvmOpts = Environment.detectJvmOptionsForServer(found, serverArgs, logger)
          val stringClasspath = classpath.map(_.normalize.toAbsolutePath).mkString(delimiter)
          val cmd = List("java") ++ finalJvmOpts(jvmOpts) ++ List(
            "-classpath",
            stringClasspath,
            "bloop.Server"
          ) ++ serverArgs
          cmd -> usedExtraJvmOpts
      }
    }

    /*
     * The process to launch the server is as follows:
     *
     *   - First we run the server with some JVM options that the launcher knows
     *     speeds up the execution of the server. This execution can fail because
     *     the JVM options are specific to the JVM.
     *   - When the first run fails, we attempt to run the server with no
     *     additional JVM options and report that to the user.
     */

    def sysproc(cmd: List[String]): StatusCommand = {
      logger.info(Feedback.startingBloopServer(config))
      logger.info(s"-> Command: $cmd")

      // Don't use `shell.runCommand` b/c it uses an executor that gets shut down upon `System.exit`
      val process = new ProcessBuilder()
        .command(cmd.toArray: _*)
        .directory(Environment.cwd.toFile)

      if (redirectOutErr) {
        process.redirectOutput()
        process.redirectError()
      }

      process.redirectErrorStream()
      val started = process.start()
      val is = started.getInputStream()
      val code = started.waitFor()
      val output = scala.io.Source.fromInputStream(is).mkString
      StatusCommand(code, output)
    }

    val start = System.currentTimeMillis()

    // Run bloop server with special performance-sensitive JVM options
    val performanceSensitiveOpts = Environment.PerformanceSensitiveOptsForBloop
    val (firstCmd, usedExtraJvmOpts) = cmdWithArgs(serverToRun, performanceSensitiveOpts)
    val firstStatus = sysproc(firstCmd)

    val end = System.currentTimeMillis()
    val elapsedFirstCmd = end - start

    // Don't run server twice, exit was successful or user args already contain performance-sensitive args
    if (firstStatus.code == 0 || !usedExtraJvmOpts) firstCmd -> firstStatus
    else {
      val isExitRelatedToPerformanceSensitiveOpts = {
        performanceSensitiveOpts.exists(firstStatus.output.contains(_)) ||
        // Output can be empty when `bloop server` bc it redirects streams; use timing as proxy
        elapsedFirstCmd <= 8000 // Use large number because launching JVMs on Windows is expensive
      }

      if (!isExitRelatedToPerformanceSensitiveOpts) firstCmd -> firstStatus
      else {
        val (secondCmd, _) = cmdWithArgs(serverToRun, Nil)
        secondCmd -> sysproc(secondCmd)
      }
    }
  }

  private def runAfterCommand(
      cmd: String,
      cmdArgs: Array[String],
      cmdOutFile: Path,
      exitCode: Int,
      logger: SnailgunLogger
  ): Int = {
    if (exitCode == 0 && cmdArgs.contains("--help")) {
      logger.info("Type `--nailgun-help` for help on the Nailgun CLI tool.")
    }

    if (exitCode != 0 && cmd == "repl") {
      // Assumes `repl` is not a valid Bloop command, provides user hint to use console instead
      logger.error(s"Command `repl` doesn't exist in Bloop, did you mean `console`?")
      1
    } else {
      val requiresAmmonite = exitCode == 0 && cmdOutFile != null && cmd == "console"
      if (!requiresAmmonite) 0
      else {
        def processAmmoniteOutFile: Int = {
          val contents = new String(Files.readAllBytes(cmdOutFile), StandardCharsets.UTF_8)
          val replCoursierCmd = contents.trim.split(" ")
          if (replCoursierCmd.length == 0) {
            logger.error("Unexpected empty REPL command after running console in Bloop server!")
            1
          } else {
            val status = shell.runCommandInheritingIO(
              replCoursierCmd.toList,
              Environment.cwd,
              None,
              attachTerminal = true
            )

            status.code
          }
        }

        val replKindFlagIndex = cmdArgs.indexOf("--repl")
        if (replKindFlagIndex < 0) processAmmoniteOutFile
        val replKind = cmdArgs(replKindFlagIndex + 1)
        if (replKind != "ammonite") 0
        else processAmmoniteOutFile
      }
    }
  }
}

object Bloopgun extends BloopgunCli("1.3.4", System.in, System.out, System.err, Shell.default) {
  def main(args: Array[String]): Unit = System.exit(run(args))
}
