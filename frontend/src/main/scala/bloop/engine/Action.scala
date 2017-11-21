package bloop.engine

import bloop.cli.{Commands, CommonOptions, ExitStatus}

sealed trait Action
case class Exit(exitStatus: ExitStatus) extends Action
case class Run(command: Commands.Command, action: Action) extends Action
case class Print(msg: String, commonOptions: CommonOptions, action: Action) extends Action
