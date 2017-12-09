package bloop.logging

import java.io.OutputStream

import org.apache.logging.log4j
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.{ConsoleAppender, OutputStreamAppender}
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.core.config.{AppenderRef, Configurator, LoggerConfig}

/** Creates a logger that is backed up by a Log4j logger. */
class BloopLogger private (override val name: String) extends AbstractLogger {
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

object BloopLogger {
  def apply(name: String): BloopLogger = new BloopLogger(name)

  private val DefaultLayout: String =
    "%highlight{%equals{[%-0.-1level] }{[I] }{}}{FATAL=white, ERROR=bright red, WARN=yellow, INFO=dim blue, DEBUG=green, TRACE=blue}%msg%n"
  private final val AppenderName = "BloopCommonOptionsAppender"

  /**
   * The following magic piece of code swaps the output stream of a current logger dynamically
   * based on the new output stream passed in by the user.
   *
   * It is inspired by http://logging.apache.org/log4j/2.x/manual/customconfig.html#AddingToCurrent
   * but it is different in that it allows subsequent modifications, and not only one modification.
   *
   * The code of the docs has two problems:
   * 1. It is not able to update the output stream twice because of some weird reference error.
   * 2. It does not preserve the previous configuration that the logger we modify had.
   *
   * As a result, the following hacky code does this two things and allows us to change the
   * output stream where the logger writes. It is supposed to be inefficient, and that's why
   * we cache this operation in a guava weak cache.
   *
   * @param logger The logger whose output stream we want to modify.
   * @param out The output stream we should redirect the logger to.
   */
  private def swapOut(logger: BloopLogger, out: OutputStream): Unit = logger.synchronized {
    val ctx = LogManager.getContext(false).asInstanceOf[LoggerContext]
    val config = ctx.getConfiguration()
    val layout = PatternLayout.newBuilder().withPattern(DefaultLayout).build()
    val appenderName = s"$AppenderName-${logger.name}"
    val appender = OutputStreamAppender
      .newBuilder()
      .setName(appenderName)
      .setLayout(layout)
      .setTarget(out)
      .setFollow(true)
      .build()

    appender.start()
    val currentLoggerConfig = config.getLoggerConfig(logger.name)
    currentLoggerConfig.removeAppender("STDOUT")
    currentLoggerConfig.removeAppender(appenderName)
    currentLoggerConfig.addAppender(appender, null, null)
    ctx.updateLoggers()
    ()
  }

  type LoggerUpdateKey = (BloopLogger, OutputStream)
  private val updateCache: scala.collection.mutable.Map[BloopLogger, OutputStream] = {
    import com.google.common.collect.MapMaker
    import scala.collection.JavaConverters._
    new MapMaker().weakKeys().makeMap[BloopLogger, OutputStream]().asScala
  }

  def update(logger: BloopLogger, out: OutputStream): Unit = {
    def swapAndCache: Unit = {
      logger.debug(s"Logger ${logger.name} has not been updated to use $out.")
      swapOut(logger, out)
      updateCache += logger -> out
      ()
    }

    updateCache.get(logger) match {
      case Some(previousOut) if previousOut == out =>
        logger.debug(s"Update of out ($out) in logger ${logger.name} is cached.")
      case _ => swapAndCache
    }
  }
}
