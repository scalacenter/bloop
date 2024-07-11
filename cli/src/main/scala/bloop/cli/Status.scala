package bloop.cli

import caseapp._

import bloop.cli.options.StatusOptions
import bloop.rifle.BloopRifle

object Status extends Command[StatusOptions] {
  override def names = List(List("status"))

  def run(options: StatusOptions, args: RemainingArgs): Unit = {
    val bloopRifleConfig = options.bloopRifleConfig
    val logger = options.logging.logger

    val isRunning = BloopRifle.check(bloopRifleConfig, logger.bloopRifleLogger)

    val status =
      if (isRunning) "running"
      else "stopped"

    println(status)
  }
}
