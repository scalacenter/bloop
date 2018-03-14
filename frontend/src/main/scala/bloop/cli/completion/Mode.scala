package bloop.cli.completion

sealed trait Mode

object Mode {
  case object Commands extends Mode
  case object Projects extends Mode
}
