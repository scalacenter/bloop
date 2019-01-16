package bloop.logging

import monix.reactive.{Observable, MulticastStrategy}
import monix.execution.Scheduler

/**
 * Defines a logger that forwards any event to the underlying logger and that can be subscribed to
 * by different clients. To subscribe to a client, you can use the [[subscribe]] method that
 * returns an `Observable[LoggerAction]`.
 */
final class ObservableLogger[L <: Logger](
    override val name: String,
    underlying: L,
    scheduler: Scheduler
) extends Logger {
  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger =
    new ObservableLogger(name, underlying.asDiscrete, scheduler)
  override def asVerbose: Logger =
    new ObservableLogger(name, underlying.asVerbose, scheduler)
  override def ansiCodesSupported: Boolean = underlying.ansiCodesSupported

  override def debugFilter: DebugFilter = underlying.debugFilter
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit =
    if (debugFilter.isEnabledFor(ctx)) printDebug(msg)

  private val (observer, observable) =
    Observable.multicast[ObservableLogger.Action](MulticastStrategy.publish)(scheduler)

  def observe: Observable[ObservableLogger.Action] = observable

  override def trace(t: Throwable): Unit = {
    underlying.trace(t)
    //observer.onNext(ObservableLogger.LogTraceMessage(msg))
    ()
  }

  override private[logging] def printDebug(msg: String): Unit = {
    underlying.printDebug(msg)
    observer.onNext(ObservableLogger.LogDebugMessage(msg))
    ()
  }

  override def error(msg: String): Unit = {
    underlying.error(msg)
    observer.onNext(ObservableLogger.LogErrorMessage(msg))
    ()
  }

  override def warn(msg: String): Unit = {
    underlying.warn(msg)
    observer.onNext(ObservableLogger.LogWarnMessage(msg))
    ()
  }

  override def info(msg: String): Unit = {
    underlying.info(msg)
    observer.onNext(ObservableLogger.LogInfoMessage(msg))
    ()
  }
}

object ObservableLogger {
  sealed trait Action
  final case class PublishBspEvent(event: BspServerEvent) extends Action
  final case class LogErrorMessage(msg: String) extends Action
  final case class LogWarnMessage(msg: String) extends Action
  final case class LogInfoMessage(msg: String) extends Action
  final case class LogDebugMessage(msg: String) extends Action
  final case class LogTraceMessage(msg: String) extends Action
}
