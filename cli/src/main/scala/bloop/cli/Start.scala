package bloop.cli

import caseapp._

import bloop.cli.options.StartOptions
import bloop.rifle.BloopThreads
import bloop.rifle.internal.BuildInfo
import bloop.rifle.BloopRifle
import scala.concurrent.Await
import scala.concurrent.duration.Duration

object Start extends Command[StartOptions] {
  override def names = List(List("start"))

  def run(options: StartOptions, args: RemainingArgs): Unit = {
    val threads = BloopThreads.create()
    val bloopRifleConfig = options.bloopRifleConfig
    val logger = options.logging.logger

    val isRunning = BloopRifle.check(bloopRifleConfig, logger.bloopRifleLogger)

    if (isRunning && options.force) {
      logger.message("Found Bloop server running, stopping it.")
      val ret = BloopRifle.exit(bloopRifleConfig, os.pwd.toNIO, logger.bloopRifleLogger)
      logger.debug(s"Bloop exit returned code $ret")
      if (ret == 0)
        logger.message("Stopped Bloop server.")
      else {
        if (options.logging.verbosity >= 0)
          System.err.println(s"Error running bloop exit command (return code $ret)")
        sys.exit(1)
      }
    }

    if (isRunning && !options.force)
      logger.message("Bloop server already running.")
    else {

      val f = BloopRifle.startServer(
        bloopRifleConfig,
        threads.startServerChecks,
        logger.bloopRifleLogger,
        BuildInfo.version,
        bloopRifleConfig.javaPath
      )
      Await.result(f, Duration.Inf)
      logger.message("Bloop server started.")
    }
  }
}
