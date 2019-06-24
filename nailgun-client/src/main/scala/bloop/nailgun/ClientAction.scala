package bloop.nailgun

import java.io.PrintStream

sealed trait ClientAction
object ClientAction {
  final case object SendStdin extends ClientAction
  final case class Exit(code: Int) extends ClientAction
  final case class ExitForcefully(error: Throwable) extends ClientAction
  final case class Print(bytes: Array[Byte], ps: PrintStream) extends ClientAction
}
