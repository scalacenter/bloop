package bloop.reporter

import xsbti.{Position, Severity}
import scala.Console.RESET
import scala.compat.Platform.EOL

import java.io.File
import java.util.Optional

import bloop.io.AbsolutePath

/**
 * Describes how messages should be formatted by a `ConfigurableReporter`.
 *
 * @param reporter The reporter that will use this format.
 */
abstract class ReporterFormat(reporter: Reporter) {

  /**
   * Returns a string representation of `Problem`, as it should be shown by
   * the reporter.
   *
   * @param problem The problem to format.
   * @return A string representation of the problem.
   */
  def formatProblem(problem: Problem): String

  /**
   * Prints a summary of all the errors reporter to the logger.
   */
  def printSummary(): Unit

  private final val enabledColor: Boolean =
    reporter.logger.ansiCodesSupported() && reporter.config.colors

  /**
   * Shows `str` with color `color` if the reporter is configured with
   * `colors = true`.
   *
   * @param color The color to use
   * @param str   The string to color.
   * @return The colored string if `colors = true`, `str` otherwise.
   */
  protected def colored(color: String, str: String): String =
    if (enabledColor) s"${RESET}${color}${str}${RESET}" else str

  /**
   * Put a prefix `prefix` at the beginning of `paragraph`, indents all lines.
   *
   * @param prefix    The prefix to insert.
   * @param paragraph The block of text to prefix and indent.
   * @return The prefixed and indented paragraph.
   */
  protected def prefixed(prefixColor: String, prefix: String, paragraph: String): String =
    paragraph.linesIterator.mkString(colored(prefixColor, prefix), EOL + " " * prefix.length, "")

  /**
   * Retrieves the right color to use for `problem` based on Severity.
   *
   * @param problem The problem to show.
   * @return The ANSI string to set the right color.
   */
  protected def colorFor(problem: Problem): String =
    problem.severity match {
      case Severity.Info => reporter.config.infoColor
      case Severity.Error => reporter.config.errorColor
      case Severity.Warn => reporter.config.warningColor
    }

  /**
   * Returns the absolute path of `file` with `cwd` stripped, if the reporter
   * if configured with `shortenPaths = true`.
   *
   * @param file The file whose path to show.
   * @return The absolute path of `file` with `cwd` stripped if `shortenPaths = true`,
   *         or the original path otherwise.
   */
  protected def showPath(file: File): Option[String] = {
    val absolutePath = Option(file).map(AbsolutePath(_))
    if (reporter.config.shortenPaths) {
      try absolutePath.map(_.toRelative(reporter.cwd).toString)
      catch { case _: IllegalArgumentException => absolutePath.map(_.toString) }
    } else absolutePath.map(_.toString)
  }

  /**
   * Returns spaces to fix alignment given the `severity`.
   */
  protected def extraSpace(severity: Severity): String =
    severity match {
      case Severity.Warn => " "
      case Severity.Info => " "
      case _ => ""
    }

  protected implicit class MyPosition(position: Position) {
    def pfile: Option[String] = toOption(position.sourceFile).flatMap(showPath)
    def pline: Option[Int] = toOption(position.line).map(_.toInt)
    def lineOffset: Option[Int] = toOption(position.pointerSpace).map(_.length)
  }

  protected def toOption[T](m: Optional[T]): Option[T] =
    if (m.isPresent) Some(m.get) else None
}
