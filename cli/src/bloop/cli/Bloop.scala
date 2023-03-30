package bloop.cli

import caseapp.core.app.CommandsEntryPoint

object Bloop extends CommandsEntryPoint {
  def progName = "bloop"
  def commands = Seq(
    Exit,
    Output,
    Start,
    Status
  )
  override def defaultCommand = Some(Default)
}
