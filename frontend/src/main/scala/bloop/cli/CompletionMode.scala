package bloop.cli

sealed trait CompletionMode

object CompletionMode {
  case object Commands extends CompletionMode
  case object Projects extends CompletionMode
}
