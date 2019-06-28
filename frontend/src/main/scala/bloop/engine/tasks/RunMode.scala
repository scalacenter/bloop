package bloop.engine.tasks

sealed trait RunMode
object RunMode {
  final case object Debug extends RunMode
  final case object Normal extends RunMode
}
