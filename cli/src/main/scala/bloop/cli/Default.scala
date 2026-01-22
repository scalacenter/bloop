package bloop.cli

import java.nio.file.Files

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.io.Source
import scala.util.Using

import bloop.cli.options.DefaultOptions
import bloop.rifle.BloopRifle
import bloop.rifle.BloopThreads
import bloop.rifle.internal.Operations

import caseapp.core.RemainingArgs
import caseapp.core.app.Command

object Default extends Command[DefaultOptions] {
  override def stopAtFirstUnrecognized = true

  def run(options: DefaultOptions, args: RemainingArgs): Unit = {

    val threads = BloopThreads.create()
    val logger = options.logging.logger
    val bloopRifleConfig = options.bloopRifleConfig

    val isRunning = BloopRifle.check(bloopRifleConfig, logger.bloopRifleLogger)

    if (isRunning)
      logger.debug("Found running Bloop server")
    else {
      logger.debug("No running Bloop server found, starting one")
      val f = BloopRifle.startServer(
        bloopRifleConfig,
        threads.startServerChecks,
        logger.bloopRifleLogger,
        bloopRifleConfig.retainedBloopVersion.version.raw,
        bloopRifleConfig.javaPath
      )
      Await.result(f, Duration.Inf)
      logger.message("Bloop server started.")
    }

    val args0 = args.all

    args0 match {
      case Seq() =>
        // FIXME Give more details?
        logger.message("Bloop server is running.")
      case Seq(cmd, cmdArgs @ _*) if cmd == "console" =>
        // Console command needs special handling because interactive REPLs
        // require direct terminal access which isn't available through nailgun
        runConsoleCommand(options, cmdArgs.toArray)
      case Seq(cmd, cmdArgs @ _*) =>
        val assumeTty = System.console() != null
        val cwd = os.pwd.wrapped
        val retCode = Operations.run(
          command = cmd,
          args = cmdArgs.toArray,
          workingDir = cwd,
          address = bloopRifleConfig.address,
          inOpt = Some(System.in),
          out = System.out,
          err = System.err,
          logger = logger.bloopRifleLogger,
          assumeInTty = assumeTty,
          assumeOutTty = assumeTty,
          assumeErrTty = assumeTty
        )
        if (retCode == 0)
          logger.debug(s"Bloop command $cmd ran successfully (return code 0)")
        else {
          logger.debug(
            s"Got return code $retCode from Bloop server when running $cmd, exiting with it"
          )
          sys.exit(retCode)
        }
    }
  }

  /**
   * Handles the console command specially by:
   * 1. Creating a temp file for the server to write the Ammonite command
   * 2. Running the console command on the server with --out-file
   * 3. Reading the command from the file and executing it locally with terminal access
   */
  private def runConsoleCommand(options: DefaultOptions, args: Array[String]): Unit = {
    val logger = options.logging.logger
    val bloopRifleConfig = options.bloopRifleConfig
    val cwd = os.pwd.wrapped

    // Create a temp file for the Ammonite command
    val outFile = Files.createTempFile("bloop-console-", ".txt")
    outFile.toFile.deleteOnExit()

    // Add --out-file to the args
    val argsWithOutFile = args ++ Array("--out-file", outFile.toString)

    val assumeTty = System.console() != null
    // Don't pass stdin to the server - we'll need it for Ammonite later
    // The server only needs to compile and write the command to the outFile
    val retCode = Operations.run(
      command = "console",
      args = argsWithOutFile,
      workingDir = cwd,
      address = bloopRifleConfig.address,
      inOpt = None,
      out = System.out,
      err = System.err,
      logger = logger.bloopRifleLogger,
      assumeInTty = false,
      assumeOutTty = assumeTty,
      assumeErrTty = assumeTty
    )

    if (retCode != 0) {
      logger.debug(s"Console command failed with return code $retCode")
      sys.exit(retCode)
    }

    // Read the command from the temp file
    if (!Files.exists(outFile) || Files.size(outFile) == 0) {
      logger.debug("Console command output file is empty or doesn't exist")
      sys.exit(1)
    }

    val consoleCmd = Using(Source.fromFile(outFile.toFile)) { source =>
      source.getLines().toArray
    }.getOrElse {
      logger.debug("Failed to read console command from output file")
      sys.exit(1)
    }

    // Clean up the temp file
    try Files.delete(outFile)
    catch { case _: Exception => }

    logger.debug(s"Running console command: ${consoleCmd.mkString(" ")}")

    // Run the command with inherited IO for proper terminal access
    import scala.jdk.CollectionConverters._
    val builder = new ProcessBuilder(consoleCmd.toList.asJava)
    builder.directory(cwd.toFile)
    builder.inheritIO()
    val process = builder.start()
    val exitCode = process.waitFor()

    if (exitCode != 0) {
      sys.exit(exitCode)
    }
  }
}
