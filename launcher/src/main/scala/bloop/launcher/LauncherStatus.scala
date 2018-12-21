package bloop.launcher

sealed trait LauncherStatus
object LauncherStatus {
  case object SuccessfulRun extends LauncherStatus
  sealed trait FailedLauncherStatus extends LauncherStatus
  case object FailedToInstallBloop extends FailedLauncherStatus
  case object FailedToOpenBspConnection extends FailedLauncherStatus
  case object FailedToConnectToServer extends FailedLauncherStatus
  case object FailedToParseArguments extends FailedLauncherStatus
}
