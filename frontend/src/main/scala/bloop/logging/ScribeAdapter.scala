package bloop.logging
import scribe.LogRecord

trait ScribeAdapter extends scribe.LoggerSupport { self: Logger =>
  override def log[M](record: LogRecord[M]): Unit = {
    import scribe.Level
    val msg = record.message
    record.level match {
      case Level.Info => info(msg)
      case Level.Error => error(msg)
      case Level.Warn => warn(msg)
      case Level.Debug => debug(msg)(DebugFilter.Bsp)
      case Level.Trace =>
        record.throwable match {
          case Some(t) => trace(t)
          case None => debug(record.message)(DebugFilter.Bsp)
        }
    }
  }
}
