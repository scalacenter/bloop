package bloop.logging

import java.io.OutputStream

import org.apache.logging.log4j
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.{OutputStreamAppender, OutputStreamManager}
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.core.config.{AppenderRef, Configurator, LoggerConfig}

/** Creates a logger that is backed up by a Log4j logger. */
class BloopLogger(override val name: String) extends AbstractLogger {
  def this(logger: Logger) = this(logger.name)
  private val logger: log4j.Logger = LogManager.getLogger(name)

  override def ansiCodesSupported() = true
  override def debug(msg: String): Unit = msg.lines.foreach(logger.debug)
  override def error(msg: String): Unit = msg.lines.foreach(logger.error)
  override def warn(msg: String): Unit = msg.lines.foreach(logger.warn)
  override def trace(exception: Throwable): Unit = logger.trace(exception)
  override def info(msg: String): Unit = msg.lines.foreach(logger.info)

  override def verbose[T](op: => T): T = {
    val initialLevel = LogManager.getRootLogger.getLevel
    Configurator.setRootLevel(Level.DEBUG)
    try op
    finally Configurator.setRootLevel(initialLevel)
  }
}

object Lof4JLogger {
  private val DefaultLayout: String =
    "%highlight{%equals{[%-0.-1level] }{[I] }{}}{FATAL=white, ERROR=bright red, WARN=yellow, INFO=dim blue, DEBUG=green, TRACE=blue}%msg%n"
  private final val LoggerName = "org.apache.logging.log4j"
  private final val AppenderName = "common-options-out"
  def swapOut(logger: Logger, out: OutputStream): Unit = synchronized(logger) {
    val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
    val config = ctx.getConfiguration()
    val layout = PatternLayout.newBuilder().withPattern(DefaultLayout)
    val manager = new OutputStreamManager(out, AppenderName, layout, true)
    val appender = new OutputStreamAppender(AppenderName, layout, null, manager, true)
    appender.start(appender)
    config.addAppender(appender)

    val refs = Array(AppenderRef.createAppenderRef(AppenderName, null, null))
    val loggerConfig: LoggerConfig =
      LoggerConfig.createLogger("false", "debug", Loggername, "true", refs, null, config, null)
    loggerConfig.addAppender(appender, null, null)
    config.addLogger(LoggerName, loggerConfig)
    ctx.updateLoggers()
  }
}
