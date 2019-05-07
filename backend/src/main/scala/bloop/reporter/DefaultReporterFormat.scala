package bloop.reporter

import scala.compat.Platform.EOL

/**
 * Helper object for easy configuration.
 */
object DefaultReporterFormat extends (Reporter => ReporterFormat) {
  override def apply(reporter: Reporter): DefaultReporterFormat =
    new DefaultReporterFormat(reporter)
}

/**
 * Default format for reporter.
 *
 * @param reporter The reporter that uses this format.
 */
class DefaultReporterFormat(reporter: Reporter) extends ReporterFormat(reporter) {

  protected def formatSourcePath(problem: Problem): Option[String] =
    problem.position.pfile.map { filePath =>
      val line = toOption(problem.position.line).map(":" + _).getOrElse("")
      val column = toOption(problem.position.pointer).map(o => s":${o + 1}").getOrElse("")
      colored(reporter.config.sourcePathColor, filePath) + s"$line$column"
    }

  protected def formatSource(problem: Problem): Option[String] = {
    val richFormatSource = {
      for {
        lineContent <- Option(problem.position.lineContent).filter(_.nonEmpty)
        startLine <- toOption(problem.position.startLine())
        endLine <- toOption(problem.position.endLine())
        startColumn <- toOption(problem.position.startColumn())
        endColumn <- toOption(problem.position.endColumn())
        if startLine == endLine
      } yield {
        val spaces = " " * startColumn
        val carets = "^" * Math.max(1, endColumn - startColumn)
        lineContent + System.lineSeparator() + spaces + carets
      }
    }

    richFormatSource.orElse {
      for {
        lineContent <- Option(problem.position.lineContent).filter(_.nonEmpty)
        pointer <- toOption(problem.position.pointerSpace)
      } yield {
        lineContent + System.lineSeparator() + pointer + "^"
      }
    }
  }

  protected def formatMessage(problem: Problem): Option[String] =
    Some(problem.message)

  override def formatProblem(problem: Problem): String = {
    val line = toOption(problem.position.line).map("L" + _).getOrElse("")
    val col = toOption(problem.position.pointer) match {
      case Some(offset) if reporter.config.columnNumbers => "C" + (offset + 1)
      case _ => ""
    }

    val sourceCode =
      formatSource(problem).map { s =>
        val prefix = line + col
        prefixed(colorFor(problem), if (prefix.nonEmpty) prefix + ": " else "", s)
      }

    val text =
      List(formatSourcePath(problem), formatMessage(problem), sourceCode).flatten
        .mkString(EOL)

    val prefix = s"${extraSpace(problem.severity)}[E${problem.id}] "
    prefixed(reporter.config.errorIdColor, prefix, text)
  }

  override def printSummary(): Unit = {
    val log: String => Unit =
      (line: String) =>
        if (reporter.hasErrors()) reporter.logger.error(line)
        else if (reporter.hasWarnings()) reporter.logger.warn(line)
        else reporter.logger.info(line)

    reporter.allProblems
      .groupBy(_.position.pfile)
      .foreach {
        case (None, _) =>
          ()
        case (Some(file), inFile) =>
          val sorted =
            inFile
              .sortBy(_.position.pline)
              .flatMap(showProblemLine)

          if (sorted.nonEmpty) {
            val line = s"""$file: ${sorted.mkString(", ")}"""
            log(line)
          }
      }

    if (reporter.config.showLegend && reporter.allProblems.nonEmpty)
      reporter.logger.info("Legend: Ln = line n, Cn = column n, En = error n")
  }

  /**
   * Shows the line at which `problem` occured and the id of the problem.
   *
   * @param problem The problem to show
   * @return A formatted string that shows the line of the problem and its id.
   */
  private def showProblemLine(problem: Problem): Option[String] =
    problem.position.pline.map { line =>
      val color = colorFor(problem)

      colored(color, "L" + line) +
        colored(reporter.config.errorIdColor, s" [E${problem.id}]")
    }

}
