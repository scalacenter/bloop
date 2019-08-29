package bloop.bloopgun

import bloop.bloopgun.core.Shell
import bloop.bloopgun.core.DependencyResolution
import bloop.bloopgun.util.Environment

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
          cmdOpt
        )
    }

    OParser.parse(cliParser, args, BloopgunParams()) match {
      case None => exit(1)
      case Some(params) =>
        if (params.nailgunShowVersion)
          out.println(s"Nailgun protocol v${Defaults.Version}")

        def process(cmd: String, initialCmdArgs: Array[String]) = {
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

          val streams = Streams(in, out, err)
          val hostServer =
            if (setServer) params.nailgunServer
            else Defaults.env.getOrElse("BLOOP_SERVER", params.nailgunServer)
          val portServer =
            if (setPort) params.nailgunPort
            else Defaults.env.getOrElse("BLOOP_PORT", params.nailgunPort.toString).toInt
          val client = TcpClient(hostServer, portServer)
          val noCancel = new AtomicBoolean(false)
          val logger = new SnailgunLogger("log", out, isVerbose = params.verbose)

          try {
            import Environment.cwd
            // Disable interactive if running with shaded bloopgun bc JNA cannot be shaded
            val shadedClass = "bloop.shaded.bloop.bloopgun.Bloopgun"
            val isInteractive = Try(getClass.getClassLoader.loadClass(shadedClass)).isFailure
            val code =
              client.run(cmd, cmdArgs, cwd, Defaults.env, streams, logger, noCancel, isInteractive)
            logger.debug(s"Return code is $code")
            runAfterCommand(cmd, cmdArgs, consoleCmdOutFile, code, logger)
            exit(code)
          } catch {
            case _: ConnectException =>
              // Attempt to start server here, move launcher logic
              errorAndExit(s"No server running in $hostServer:$portServer!")
          } finally {
            if (consoleCmdOutFile != null) {
              Files.deleteIfExists(consoleCmdOutFile)
            }
            ()
          }
        }

        params.args match {
          case Nil if params.help => process("help", Array.empty)
          case Nil => errorAndExit("Missing CLI command for Bloop server!")
          case cmd :: cmdArgs => process(cmd, cmdArgs.toArray)
        }
    }
  }

  def errorAndExit(msg: String, code: Int = 1): Unit = { err.println(msg); exit(code) }
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
      errorAndExit(s"Command `repl` doesn't exist in Bloop, did you mean `console`?")
    }

    if (exitCode == 0 && cmdOutFile != null && cmd == "console") {
      def processAmmoniteOutFile = {
        val contents = new String(Files.readAllBytes(cmdOutFile), StandardCharsets.UTF_8)
        val replCoursierCmd = contents.trim.split(" ")
        if (replCoursierCmd.length == 0) {
          errorAndExit("Unexpected empty REPL command after running console in Bloop server!")
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
