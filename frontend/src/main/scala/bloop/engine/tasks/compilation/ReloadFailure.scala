package bloop.engine.tasks.compilation

/**
 * Why a reload did not happen. Clients branch on `retryable`: a busy build settles on its own,
 * whereas unusable state needs the caller to fix what is on disk.
 */
sealed trait ReloadFailure {
  def message: String

  /** Stable identifier for clients, independent of the names used in this source. */
  def reason: String
  def retryable: Boolean
}

object ReloadFailure {

  /** Projects are compiling, so their state is about to change. */
  final case class Busy(message: String) extends ReloadFailure {
    val reason: String = "busy"

    val retryable: Boolean = true
  }

  /** Projects are being cleaned, so the state to replace is being removed. */
  final case class Cleaning(message: String) extends ReloadFailure {
    val reason: String = "cleaning"

    val retryable: Boolean = true
  }

  /** The state of the projects changed while it was read from disk. */
  final case class ConcurrentChange(message: String) extends ReloadFailure {
    val reason: String = "concurrent-change"

    val retryable: Boolean = true
  }

  /** The state persisted on disk exists but cannot be used. */
  final case class UnusableState(message: String) extends ReloadFailure {
    val reason: String = "unusable-state"

    val retryable: Boolean = false
  }

  /** The requested targets do not name projects of this build. */
  final case class UnknownTargets(message: String) extends ReloadFailure {
    val reason: String = "unknown-targets"

    val retryable: Boolean = false
  }
}
