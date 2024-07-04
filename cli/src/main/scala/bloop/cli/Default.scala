package bloop.cli

import caseapp.core.RemainingArgs

import bloop.cli.options.DefaultOptions
import bloop.rifle.BloopThreads
import bloop.rifle.BloopRifle
import bloop.rifle.internal.Operations
import scala.concurrent.Await
import scala.concurrent.duration.Duration
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
      case Seq(cmd, args @ _*) =>
        val assumeTty = System.console() != null
        val cwd = os.pwd.wrapped
        val retCode = Operations.run(
          command = cmd,
          args = args.toArray,
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
}
