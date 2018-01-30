package bloop.logging

import java.io.{FileOutputStream, OutputStream, PrintStream}

import bloop.io.AbsolutePath
import org.apache.logging.log4j
import org.apache.logging.log4j.{Level, LogManager}
import org.apache.logging.log4j.core.{Filter, LoggerContext}
import org.apache.logging.log4j.core.appender.{ConsoleAppender, OutputStreamAppender}
import org.apache.logging.log4j.core.filter.{CompositeFilter, Filterable, ThresholdFilter}
import org.apache.logging.log4j.core.layout.PatternLayout
import org.apache.logging.log4j.core.config.{AppenderRef, Configurator, LoggerConfig}

/**
 * Creates a logger that is backed up by a Log4j logger.
 *
 * @param name The name of this logger.
 * @param out  The stream to use to write `INFO` and `WARN` level messages.
 * @param err  The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
 */
class BloopLogger(override val name: String, out: PrintStream, err: PrintStream)
    extends AbstractLogger {
  private val logger: log4j.Logger = LogManager.getLogger(name)

  private val outAppender = createAppender(out, "out", BloopLogger.outFilter)
  private val errAppender = createAppender(err, "err", BloopLogger.normalErrFilter)

  initialize()

  override def ansiCodesSupported() = true
  override def debug(msg: String): Unit = msg.lines.foreach(logger.debug)
  override def error(msg: String): Unit = msg.lines.foreach(logger.error)
  override def warn(msg: String): Unit = msg.lines.foreach(logger.warn)
  override def trace(exception: Throwable): Unit = logger.trace(exception)
  override def info(msg: String): Unit = msg.lines.foreach(logger.info)

  override def verbose[T](op: => T): T = {
    val previousFilter = errAppender.getFilter()
    val newFilter = BloopLogger.verboseErrFilter
    BloopLogger.switchFilters(errAppender, previousFilter, newFilter)
    try op
    finally BloopLogger.switchFilters(errAppender, newFilter, previousFilter)
  }

  private[this] def initialize(): Unit = BloopLogger.synchronized {
    removeAllAppenders()

    outAppender.start()
    errAppender.start()

    val coreLogger = logger.asInstanceOf[log4j.core.Logger]
    coreLogger.addAppender(outAppender)
    coreLogger.addAppender(errAppender)
  }

  private[this] def removeAllAppenders(): Unit = BloopLogger.synchronized {
    val coreLogger = logger.asInstanceOf[log4j.core.Logger]
    val appenders = coreLogger.getAppenders()
    appenders.forEach {
      case (_, appender) => coreLogger.removeAppender(appender)
    }
  }

  /**
   * Instantiate an `Appender` for writing to `stream`, using filter `filter`.
   *
   * @param stream     The stream to which this `Appender` should write.
   * @param nameSuffix A suffix to add to this `Appender`'s name, unique per instance.
   * @param filter     The filter to use to determine whether to accept a log event.
   * @return An `Appender` writing to `stream` the log events that `filter` accepts.
   */
  private[this] def createAppender(stream: PrintStream,
                                   nameSuffix: String,
                                   filter: Filter): OutputStreamAppender = {
    val layout = PatternLayout.newBuilder().withPattern(BloopLogger.DefaultLayout).build()
    val appender = OutputStreamAppender
      .newBuilder()
      .setName(s"$name-$nameSuffix")
      .setFilter(filter)
      .setLayout(layout)
      .setTarget(stream)
      .build()

    appender
  }

}

object BloopLogger {

  /**
   * Instantiates a new `BloopLogger` using the specified streams.
   *
   * @param name The name of the logger.
   * @param out  The stream to use to write `INFO` and `WARN` level messages.
   * @param err  The stream to use to write `FATAL`, `ERROR`, `DEBUG` and `TRACE` level messages.
   * @return A `BloopLogger` whose output will be written in the specified streams.
   */
  def at(name: String, out: PrintStream, err: PrintStream): BloopLogger =
    new BloopLogger(name, out, err)

  /**
    * Instantiates a new `BloopLogger` that redirects everything to the file.
    *
    * Unused now, but we'll need it in the future.
    *
    * @param name The name of the logger.
    * @param logFile The file where logs are written.
    * @return A `BloopLogger` backed up by a file.
    */
  def atFile(name: String, logFile: AbsolutePath): BloopLogger = {
    val outputForFile = new PrintStream(new FileOutputStream(logFile.toFile))
    BloopLogger.at(name, outputForFile, outputForFile)
  }

  /**
   * Instantiates a new `BloopLogger` that writes to stdout and stderr.
   *
   * @param name The name of the logger.
   * @return A `BloopLogger` writing to stdout and stderr. Calling this method is equivalent to
   *         calling `at(name, System.out, System.err)`.
   */
  def default(name: String): BloopLogger = at(name, System.out, System.err)

  private val DefaultLayout: String =
    "%highlight{%equals{[%-0.-1level] }{[I] }{}}{FATAL=white, ERROR=bright red, WARN=yellow, INFO=dim blue, DEBUG=green, TRACE=blue}%msg%n"
  private final val AppenderName = "BloopCommonOptionsAppender"

  /**
   * A filter that accepts events whose `level` is `level` or more specific. For less
   * specific events, this filter returns `NEUTRAL`.
   *
   * @param level The least specific `Level` that this filter should accept.
   * @return A filter that accepts events at level `Level` or more specific.
   */
  private[this] def accept(level: Level) = {
    ThresholdFilter.createFilter(level, Filter.Result.ACCEPT, Filter.Result.NEUTRAL)
  }

  /**
   * A filter that rejects events whose `level` is `level` or more specific. For less
   * specific events, this filter returns `NEUTRAL`.
   *
   * @param level The least specific `Level` that this filter should reject.
   * @return A filter that rejects events at level `Level` or more specific.
   */
  private[this] def deny(level: Level) = {
    ThresholdFilter.createFilter(level, Filter.Result.DENY, Filter.Result.NEUTRAL)
  }

  /** A filter that rejects all messages. */
  private[this] val denyAll = {
    deny(Level.ALL)
  }

  /**
   * The filter for writing to the `out` stream. Accepts messages between levels
   * `WARN` and `INFO`. All other messages are rejected.
   */
  private val outFilter = {
    val rejectErrorAndUp = deny(Level.ERROR)
    val acceptInfoAndUp = accept(Level.INFO)
    CompositeFilter.createFilters(Array(rejectErrorAndUp, acceptInfoAndUp, denyAll))
  }

  /**
   * The filter for writing to the `err` stream. Accepts messages at level `ERROR` and
   * up. All other messages are rejected.
   */
  private val normalErrFilter = {
    val acceptErrorAndUp = accept(Level.ERROR)
    CompositeFilter.createFilters(Array(acceptErrorAndUp, denyAll))
  }

  /**
   * The filter for writing to the `err` stream when the logger is in `verbose` mode.
   * Accepts messages at levels `ERROR` and up, and below `INFO`. Messages between levels
   * `WARN` and `INFO` are rejected.
   */
  private val verboseErrFilter = {
    val acceptErrorAndUp = accept(Level.ERROR)
    val rejectInfoAndUp = deny(Level.INFO)
    val acceptAll = accept(Level.ALL)
    CompositeFilter.createFilters(Array(acceptErrorAndUp, rejectInfoAndUp, acceptAll))
  }

  /**
   * Replaces the filter `previousFilter` with `newFilter` in `filterable`.
   *
   * @param filterable     The `Filterable` for which filters should be replaced.
   * @param previousFilter The current filter.
   * @param newFilter      The new filter to install in place of `previousFilter`.
   */
  private def switchFilters(filterable: Filterable,
                            previousFilter: Filter,
                            newFilter: Filter): Unit = {
    filterable.removeFilter(previousFilter)
    filterable.addFilter(newFilter)
  }
}
