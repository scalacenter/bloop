package bloop.engine

import bloop.cli.Commands
import bloop.cli.CommonOptions
import bloop.cli.ExitStatus

sealed trait Action
case class Exit(exitStatus: ExitStatus) extends Action
case class Run(command: Commands.Command, next: Action) extends Action
case class Print(msg: String, commonOptions: CommonOptions, next: Action) extends Action

object Run {
  def apply(command: Commands.Command): Run = Run(command, Exit(ExitStatus.Ok))
}
