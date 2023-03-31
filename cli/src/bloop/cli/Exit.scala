package bloop.cli

import caseapp._

import bloop.cli.options.ExitOptions
import bloop.rifle.{BloopRifle, BloopRifleConfig}

object Exit extends Command[ExitOptions] {
  override def names = List(List("exit"))

  private def mkBloopRifleConfig(opts: ExitOptions): BloopRifleConfig = {
    import opts._
    compilationServer.bloopRifleConfig(
      logging.logger,
      coursier.coursierCache(logging.logger.coursierLogger("Downloading Bloop")),
      "java", // shouldn't be used…
      directories.directories
    )
  }

  def run(options: ExitOptions, args: RemainingArgs): Unit = {
    val bloopRifleConfig = mkBloopRifleConfig(options)
    val logger           = options.logging.logger

    val isRunning = BloopRifle.check(bloopRifleConfig, logger.bloopRifleLogger)

    if (isRunning) {
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
    else
      logger.message("No running Bloop server found.")
  }
}
