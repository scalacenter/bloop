package bloop.logging

import scribe.Level
import scribe.LogRecord

trait ScribeAdapter extends scribe.LoggerSupport { self: Logger =>
  override def log[M](record: LogRecord[M]): Unit = {
    val msg = record.logOutput.plainText
    record.level match {
      case Level.Info => info(msg)
      case Level.Error => error(msg)
      case Level.Warn => warn(msg)
      case Level.Debug => debug(msg)(DebugFilter.Bsp)
      case Level.Trace =>
        record.throwable match {
          case Some(t) => trace(t)
          case None => debug(msg)(DebugFilter.Bsp)
        }
      case Level.Fatal => error(msg)
      case _ => ()
    }
  }
}
