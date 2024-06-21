package bloop.cli

import caseapp.core.app.CommandsEntryPoint
import caseapp.core.app.Command

object Bloop extends CommandsEntryPoint {
  def progName: String = "bloop"
  def commands: Seq[Command[_]] = Seq(
    Exit,
    Output,
    Start,
    Status
  )
  override def defaultCommand: Option[Command[_]] = Some(Default)
}
