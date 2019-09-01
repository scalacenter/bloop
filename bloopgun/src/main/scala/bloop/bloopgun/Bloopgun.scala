package bloop.bloopgun

import bloop.bloopgun.core.Shell
import bloop.bloopgun.core.DependencyResolution
import bloop.bloopgun.util.Environment
import bloop.bloopgun.util.Feedback
import bloop.bloopgun.core.AvailableAt
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

/**
 *
 * Supports:
 *   1. `bloop server` to start server right away. Does it make sense to keep it?
 *   4. Custom banner if error happens when connecting to server.
 *   2. DONE: `bloop help` recommends user to use `bloopgun --nailgun-help`. done
 *   3. DONE: `bloop console`, invoking Ammonite if repl kind matches.
 *   5. DONE: Custom error if user types `bloop repl`
 *
 *
 * What should `bloop server` do?
 *   1. Detect if server is running
 *   2. If it is running and version matches, do nothing and print successful message
 *   3. If it's running and versions are recognized to be incompatible, print error saying that bloop doesn't match.
 *   4. If it's not running, detect JVM options and start server preventing races.
 *   5. At the end of the execution, the server is running with the right JVM options.
 *
 * Who should call `bloop server`? Should we call bloop server if bloopgun
 * fails to establish a connection with the server during the execution of a
 * normal command?
 *
 * Pros:
 *   1. Easier, users don't need to think about starting the server manually
 *   1. We don't need to support services in Windows
 * Cons:
 *   1. The overhead of attempting to start the server can be too much.
 *   1. Confusing story to handle the lifetime of the bloop server. What should the user do if it wants to restart the server? How does that affect other clients using the server in the background?
 *
 * What happens if a command attempts to start the server but it fails because it's incompatible?
 *
 * 1. We can ask the user to do `bloop exit` and then run the command again.
 *    `bloop exit` should notify to connected clients that a client has decided to kill/restart the server.
 *
 * Proposed solution:
 *   1. `bloop server restart` and `bloop server shutdown` will try to first use the System-specific ways of managing the lifetime of the server.
 */
abstract class BloopgunCli(in: InputStream, out: PrintStream, err: PrintStream, shell: Shell) {
  def exit(code: Int): Unit
  def run(args: Array[String]): Unit = {
    var setServer: Boolean = false
    var setPort: Boolean = false
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
        .action((arg, params) => params.copy(args = params.args ++ List(arg)))
        .text("The command and arguments for the Bloop server")
      val serverCmd = builder
        .cmd("server")
        .action((_, params) => params.copy(server = true))
        .children(
          builder
            .arg[String]("<nailgun-host>")
            .optional()
            .maxOccurs(1)
            .action {
              case (arg, params) =>
                params.copy(serverParams = params.serverParams.copy(host = Some(arg)))
            },
          builder
            .arg[Int]("<nailgun-port>")
            .optional()
            .maxOccurs(1)
            .action {
              case (arg, params) =>
                params.copy(serverParams = params.serverParams.copy(port = Some(arg)))
            }
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

    OParser.parse(cliParser, args, BloopgunParams()) match {
      case None => exit(1)
      case Some(params) =>
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
          logger.info(s"HELLO! ${params.serverParams}")
          exit(0)
        } else {
          params.args match {
            case Nil if params.help => fireCommand("help", Array.empty, params, config, logger)
            case Nil => errorAndExit("Missing CLI command for Bloop server!", logger)
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
  ) = {
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
      exit(code)
    }

    try executeCmd(client)
    catch {
      case _: ConnectException =>
        // Attempt to start server here, move launcher logic
        logger.info(s"No server running in ${config.host}:${config.port}, let's fire one...")
        fireServer(params, config, "1.3.2", logger) match {
          case Some(l: ListeningAndAvailableAt) => executeCmd(client)
          case None => errorAndExit(Feedback.serverCouldNotBeStarted(config), logger)
        }
    } finally {
      if (consoleCmdOutFile != null) {
        Files.deleteIfExists(consoleCmdOutFile)
      }
      ()
    }
  }

  def fireServer(
      params: BloopgunParams,
      config: ServerConfig,
      bloopVersion: String,
      logger: SnailgunLogger
  ): Option[ListeningAndAvailableAt] = {
    // FIXME: Add logic to grab jvm options
    val serverJvmOptions: List[String] = Nil

    ServerStatus.findServerToRun(bloopVersion, shell, out).flatMap { found =>
      val cmd = found match {
        case AvailableAt(cmd) => cmd
        case ResolvedAt(classpath) =>
          val delimiter = if (Environment.isWindows) ";" else ":"
          val opts = serverJvmOptions.map(_.stripPrefix("-J"))
          val stringClasspath = classpath.map(_.normalize().toAbsolutePath).mkString(delimiter)
          List("java") ++ opts ++ List("-classpath", stringClasspath, "bloop.Server")
      }

      val statusPromise = Promise[StartStatus]()
      val serverThread = startServerInBackground(cmd, serverJvmOptions, config, statusPromise)
      logger.info("Server was started in a thread, waiting until it's up and running...")

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
        listening = shell.connectToBloopPort(cmd, port, out)
      }

      // Either listening exists or status promise is completed
      listening.orElse {
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
        shell.connectToBloopPort(cmd, port, out)
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
      runningServer: Promise[StartStatus]
  ): Thread = {
    // Always keep a server running in the background by making it a daemon thread
    shell.startThread("bloop-server-background", true) {
      val serverArgs = (config.host, config.port) match {
        case (Some(host), Some(port)) => List(host, port.toString)
        case (None, Some(port)) => List(port.toString)
        case (None, None) => Nil
        case (Some(host), None) =>
          out.println(Feedback.unexpectedServerArgsSyntax(host))
          Nil
      }

      val startCmd = binary ++ serverArgs ++ jvmOptions
      out.println(Feedback.startingBloopServer(startCmd))
      val status = shell.runCommand(startCmd, Environment.cwd, Some(15))
      runningServer.success(Some(startCmd.mkString(" ") -> status))

      ()
    }
  }

  def errorAndExit(msg: String, logger: SnailgunLogger, code: Int = 1): Unit = {
    logger.error(msg); exit(code)
  }

  private def runAfterCommand(
      cmd: String,
      cmdArgs: Array[String],
      cmdOutFile: Path,
      exitCode: Int,
      logger: SnailgunLogger
  ): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    println(
      DependencyResolution.resolve("ch.epfl.scala", "bloop-frontend_2.12", "1.3.2", System.out)
    )
    if (exitCode == 0 && cmdArgs.contains("--help")) {
      out.println("Type `--nailgun-help` for help on the Nailgun CLI tool.")
    }

    if (exitCode != 0 && cmd == "repl") {
      // Assumes `repl` is not a valid Bloop command, provides user hint to use console instead
      errorAndExit(s"Command `repl` doesn't exist in Bloop, did you mean `console`?", logger)
    }

    if (exitCode == 0 && cmdOutFile != null && cmd == "console") {
      def processAmmoniteOutFile = {
        val contents = new String(Files.readAllBytes(cmdOutFile), StandardCharsets.UTF_8)
        val replCoursierCmd = contents.trim.split(" ")
        if (replCoursierCmd.length == 0) {
          errorAndExit(
            "Unexpected empty REPL command after running console in Bloop server!",
            logger
          )
        } else {
          val status = shell.runCommandInheritingIO(
            replCoursierCmd.toList,
            Environment.cwd,
            None,
            attachTerminal = true
          )

          exit(status.code)
        }
      }

      val replKindFlagIndex = cmdArgs.indexOf("--repl")
      if (replKindFlagIndex < 0) processAmmoniteOutFile
      val replKind = cmdArgs(replKindFlagIndex + 1)
      if (replKind != "ammonite") ()
      else processAmmoniteOutFile
    }
  }
}

object Bloopgun extends BloopgunCli(System.in, System.out, System.err, Shell.default) {
  def main(args: Array[String]): Unit = run(args)
  override def exit(code: Int) = System.exit(code)
}
