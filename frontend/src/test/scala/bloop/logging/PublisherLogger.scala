package bloop.logging

import monix.reactive.Observer

final class PublisherLogger(
    observer: Observer.Sync[(String, String)],
    override val debugFilter: DebugFilter
) extends RecordingLogger {
  override val ansiCodesSupported: Boolean = false

  override def add(key: String, value: String): Unit = {
    // Ignore clean screen to show all infos
    if (value == "\u001b[H\u001b[2J") ()
    else {
      observer.onNext((key, value))
      super.add(key, value)
    }
  }

  override def isVerbose: Boolean = true
  override def asVerbose: Logger = this
  override def asDiscrete: Logger = this
}
