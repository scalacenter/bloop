package bloop.rifle

import scala.concurrent.duration.Duration

abstract class FailedToStartServerException(message: String) extends Exception(message)

final class FailedToStartServerExitCodeException(exitCode: Int)
    extends FailedToStartServerException(f"Server failed with exit code $exitCode")

final class FailedToStartServerTimeoutException(timeout: Duration)
    extends FailedToStartServerException(f"Server didn't start after $timeout")
