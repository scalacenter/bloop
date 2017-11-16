package bloop

import bloop.cli.{Commands}
import caseapp.{CommandApp, RemainingArgs}

object BloopCli extends CommandApp[Commands.Command] {
  // TODO: To be filled in via `BuildInfo`.
  override def appName: String = "bloop"
  override def appVersion: String = "0.1.0"

  override def run(options: Commands.Command, remainingArgs: RemainingArgs): Unit =
    println("Hello, world!")
}
