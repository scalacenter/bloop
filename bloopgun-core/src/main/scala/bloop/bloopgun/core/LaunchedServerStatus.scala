package bloop.bloopgun.core

sealed trait LaunchedServerStatus
object LaunchedServerStatus {
  case object SuccessfulStart extends LaunchedServerStatus
  case object FailedToStart extends LaunchedServerStatus
}
