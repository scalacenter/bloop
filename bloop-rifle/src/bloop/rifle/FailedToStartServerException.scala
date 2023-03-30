package bloop.rifle

import scala.concurrent.duration.Duration
import java.nio.file.Path

final class FailedToStartServerException(
  timeoutOpt: Option[Duration] = None,
  outputFileOpt: Option[Path] = None
) extends Exception(
      "Server didn't start" +
        timeoutOpt.fold("")(t => s" after $t") +
        outputFileOpt.fold("")(p => s", $p may contain more details.")
    )
