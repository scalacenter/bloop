package bloop.logging

import bloop.reporter.ReporterAction

import monix.reactive.Observer

/**
 * Defines a logger that forwards any event to the underlying logger and that
 * can be subscribed to by different clients. To subscribe to a client, you can
 * use the [[subscribe]] method that returns an `Observable[LoggerAction]`.
 */
final class ObservedLogger[+UseSiteLogger <: Logger] private (
    val underlying: UseSiteLogger,
    val observer: Observer[Either[ReporterAction, LoggerAction]]
) extends Logger {
  override val name: String = s"observable-${underlying.name}"
  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger = new ObservedLogger(underlying.asDiscrete, observer)
  override def asVerbose: Logger = new ObservedLogger(underlying.asVerbose, observer)
  override def ansiCodesSupported: Boolean = underlying.ansiCodesSupported
  override def withOriginId(originId: Option[String]): Logger =
    new ObservedLogger(underlying.withOriginId(originId), observer)

  override def debugFilter: DebugFilter = underlying.debugFilter
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit =
    if (debugFilter.isEnabledFor(ctx)) printDebug(msg)

  /**
   * Replay an action produced during a bloop execution by another logger.
   */
  def replay(action: LoggerAction): Unit = {
    action match {
      case LoggerAction.LogErrorMessage(msg) => error(msg)
      case LoggerAction.LogWarnMessage(msg) => warn(msg)
      case LoggerAction.LogInfoMessage(msg) => info(msg)
      case LoggerAction.LogDebugMessage(msg) => printDebug(msg)
      case LoggerAction.LogTraceMessage(msg) => printDebug(msg)
    }
  }

  override def trace(t: Throwable): Unit = {
    underlying.trace(t)
    // TODO(jvican): What to do with traces? Let's try to add logic to => String
    // observer.onNext(ObservableLogger.LogTraceMessage(msg))
    ()
  }

  override private[logging] def printDebug(msg: String): Unit = {
    underlying.printDebug(msg)
    observer.onNext(Right(LoggerAction.LogDebugMessage(msg)))
    ()
  }

  override def error(msg: String): Unit = {
    underlying.error(msg)
    observer.onNext(Right(LoggerAction.LogErrorMessage(msg)))
    ()
  }

  override def warn(msg: String): Unit = {
    underlying.warn(msg)
    observer.onNext(Right(LoggerAction.LogWarnMessage(msg)))
    ()
  }

  override def info(msg: String): Unit = {
    underlying.info(msg)
    observer.onNext(Right(LoggerAction.LogInfoMessage(msg)))
    ()
  }
}

object ObservedLogger {
  def apply[L <: Logger](
      underlying: L,
      observer: Observer[Either[ReporterAction, LoggerAction]]
  ): ObservedLogger[L] =
    new ObservedLogger(underlying, observer)

  import monix.execution.Scheduler
  def dummy(underlying: Logger, scheduler: Scheduler): ObservedLogger[Logger] = {
    ObservedLogger(underlying, Observer.empty(scheduler))
  }
}
