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
      val verboseOpt = builder
        .opt[Unit]("verbose")
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
          verboseOpt,
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

    OParser.parse(cliParser, args, BloopgunParams(), setup) match {
      case None => 1
      case Some(params0) =>
        val params = params0.copy(args = additionalCmdArgs)
        val logger = new SnailgunLogger("log", out, isVerbose = params.verbose)
        if (params.nailgunShowVersion)
          logger.info(s"Nailgun protocol v${Defaults.Version}")

        val config = ServerConfig(
          if (setServer) Some(params.nailgunServer)
          else Defaults.env.get("BLOOP_SERVER"),
          if (setPort) Some(params.nailgunPort)
          else Defaults.env.get("BLOOP_PORT").map(_.toInt)
        )

        if (params.server) {
          shell.connectToBloopPort(Nil, config, logger) match {
            case Some(_) => logger.info(s"Server is already running at $config, exiting!"); 0
            case None => fireCommand("about", Array.empty, params, config, logger)
          }
        } else {
          params.args match {
            case Nil if params.help => fireCommand("help", Array.empty, params, config, logger)
            case Nil => logger.error("Missing CLI command for Bloop server!"); 1
            case cmd :: cmdArgs => fireCommand(cmd, cmdArgs.toArray, params, config, logger)
          }
        }
    }
  }

  // Disable interactive if running with shaded bloopgun bc JNA cannot be shaded
  private val isInteractive = {
    val shadedClass = "bloop.shaded.bloop.bloopgun.Bloopgun"
    Try(getClass.getClassLoader.loadClass(shadedClass)).isFailure
  }

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
            fireServer(params, config, bloopVersion, logger) match {
              case Some(l: ListeningAndAvailableAt) => executeCmd(client)
              case None => logger.error(Feedback.serverCouldNotBeStarted(config)); 1
            }
        }

    } finally {
      if (consoleCmdOutFile != null) {
        Files.deleteIfExists(consoleCmdOutFile)
      }
      ()
    }
  }

  /**
   * Reads all jvm options required to start the Bloop server, in order of priority:
   *
   * 1. Read `$HOME/.bloop/.jvmopts` file.
   * 2. Read `.jvmopts` file right next to the location of the bloop server jar.
   * 3. Parse `-J` prefixed jvm options in the arguments passed to the server command.
   *
   * Returns a list of jvm options with no `-J` prefix.
   */
  def readAllServerJvmOptions(
      server: LocatedServer,
      serverArgs: List[String],
      logger: SnailgunLogger
  ): List[String] = {
    def readJvmOptsFile(jvmOptsFile: Path): List[String] = {
      if (!Files.isReadable(jvmOptsFile)) {
        if (Files.exists(jvmOptsFile)) {
          logger.error(s"Ignored unreadable ${jvmOptsFile.toAbsolutePath()}")
        }

        Nil
      } else {
        val contents = new String(Files.readAllBytes(jvmOptsFile), StandardCharsets.UTF_8)
        contents.linesIterator.toList
      }
    }

    val jvmOptionsFromHome = readJvmOptsFile(Environment.defaultBloopDirectory.resolve(".jvmopts"))
    val jvmOptionsFromPathNextToBinary = server match {
      case AvailableAtPath(binary) => readJvmOptsFile(binary.getParent.resolve(".jvmopts"))
      case _ => Nil
    }

    val jvmServerArgs = serverArgs.filter(_.startsWith("-J"))
    (jvmOptionsFromHome ++ jvmOptionsFromPathNextToBinary ++ jvmServerArgs).map(_.stripPrefix("-J"))
  }

  def fireServer(
      params: BloopgunParams,
      config: ServerConfig,
      bloopVersion: String,
      logger: SnailgunLogger
  ): Option[ListeningAndAvailableAt] = {
    ServerStatus.findServerToRun(bloopVersion, config, shell, logger).flatMap { found =>
      val serverJvmOpts = readAllServerJvmOptions(found, config.serverArgs, logger)
      val cmd = found match {
        case AvailableWithCommand(cmd) => cmd
        case AvailableAtPath(path) => List(path.toAbsolutePath.toString)
        case ResolvedAt(classpath) =>
          val delimiter = if (Environment.isWindows) ";" else ":"
          val stringClasspath = classpath.map(_.normalize().toAbsolutePath).mkString(delimiter)
          List("java") ++ serverJvmOpts ++ List("-classpath", stringClasspath, "bloop.Server")
      }

      val statusPromise = Promise[StartStatus]()
      val serverThread =
        startServerInBackground(cmd, serverJvmOpts, config, statusPromise, logger)
      logger.debug("Server was started in a thread, waiting until it's up and running...")

      val port = config.userOrDefaultPort
      var listening: Option[ListeningAndAvailableAt] = None
      def isConnected = listening.exists(_.isInstanceOf[ListeningAndAvailableAt])

      /*
       * Retry connecting to the server for a bunch of times until we get some
       * response the server is up and running. The server can be slow to start
       * up, particularly in Windows systems where antivirus usually have a
       * high tax on application startup times. This operation usually takes
       * around a second on Linux and Unix systems.
       */
      while (!statusPromise.isCompleted && !isConnected) {
        val waitMs = 125.toLong
        Thread.sleep(waitMs)
        logger.debug(s"Sleeping for ${waitMs}ms until we connect to server port $port")
        listening = shell.connectToBloopPort(cmd, config, logger)
      }

      // Either listening exists or status promise is completed
      listening
        .orElse {
          statusPromise.future.value match {
            case Some(scala.util.Success(Some((cmd, status)))) =>
              logger.error(s"Command '$cmd' finished with ${status.code}: '${status.output}'")
            case Some(scala.util.Failure(t)) =>
              logger.error(s"Unexpected exception thrown by thread starting server: '$t'")
            case unexpected =>
              logger.error(s"Unexpected error when starting server: $unexpected")
          }

          // Required to protect ourselves from other clients racing to start the server
          logger.info("Attempt connection a last time before giving up...")
          Thread.sleep(500.toLong)
          shell.connectToBloopPort(cmd, config, logger)
        }
    }
  }

  // Reused across the two different ways we can run a server
  type StartStatus = Option[(String, StatusCommand)]

  /**
   * Start a server in the background by using the python script `bloop server`.
   *
   * This operation can take a while in some operating systems (most notably Windows, Unix is fast).
   * After running a thread in the background, we will wait until the server is up.
   *
   * @param binary The list of arguments that make the python binary script we want to run.
   */
  def startServerInBackground(
      binary: List[String],
      jvmOptions: List[String],
      config: ServerConfig,
      runningServer: Promise[StartStatus],
      logger: Logger
  ): Thread = {
    // Always keep a server running in the background by making it a daemon thread
    shell.startThread("bloop-server-background", true) {
      val serverArgs = (config.host, config.port) match {
        case (Some(host), Some(port)) => List(host, port.toString)
        case (None, Some(port)) => List(port.toString)
        case (None, None) => Nil
        case (Some(host), None) =>
          logger.warn(Feedback.unexpectedServerArgsSyntax(host))
          Nil
      }

      val startCmd = binary ++ serverArgs ++ jvmOptions
      logger.info(Feedback.startingBloopServer(config))
      logger.debug(s"Start command: $startCmd")

      // Don't use `shell.runCommand` b/c it uses an executor that gets shut down upon `System.exit`
      val code = new ProcessBuilder()
        .command(startCmd.toArray: _*)
        .directory(Environment.cwd.toFile)
        .start()
        .waitFor()

      runningServer.success(Some(startCmd.mkString(" ") -> StatusCommand(code, "")))

      ()
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

object Bloopgun extends BloopgunCli("1.3.2", System.in, System.out, System.err, Shell.default) {
  def main(args: Array[String]): Unit = System.exit(run(args))
}
