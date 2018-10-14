package bloop.logging

sealed trait LogContext { self =>

  import LogContext._

  def isEnabled(implicit other: LogContext): Boolean =
    (self, other) match {
      case (All, _) => true
      case (_, All) => true
      case (ctx1, ctx2) if ctx1 == ctx2 => true
      case _ => false
    }
}

object LogContext {

  case object All extends LogContext
  case object FileWatching extends LogContext
  case object Compilation extends LogContext
  case object Test extends LogContext
  case object Bsp extends LogContext
}
