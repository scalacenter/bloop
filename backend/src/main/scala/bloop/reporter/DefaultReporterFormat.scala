package bloop.reporter

import scala.compat.Platform.EOL

/**
 * Helper object for easy configuration.
 */
object DefaultReporterFormat extends (ConfigurableReporter => ReporterFormat) {
  override def apply(reporter: ConfigurableReporter): DefaultReporterFormat =
    new DefaultReporterFormat(reporter)

  def toOption[T](m: java.util.Optional[T]): Option[T] = if (m.isPresent) Some(m.get) else None
  def position(position: xsbti.Position): Option[(Int, Int)] = {
    toOption(position.line).flatMap(l => toOption(position.pointer).map(c => (l, c)))
  }
}

/**
 * Default format for reporter.
 *
 * @param reporter The reporter that uses this format.
 */
class DefaultReporterFormat(reporter: ConfigurableReporter) extends ReporterFormat(reporter) {

  protected def formatSourcePath(problem: Problem): Option[String] =
    problem.position.pfile.map { f =>
      colored(reporter.config.sourcePathColor, f)
    }

  protected def formatSource(problem: Problem): Option[String] =
    for {
      line <- Option(problem.position.lineContent).filter(_.nonEmpty)
      sp <- toOption(problem.position.pointerSpace)
    } yield {
      s"""$line
         |$sp^""".stripMargin
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
