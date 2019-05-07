package bloop.reporter

import xsbti.{Position, Severity}
import scala.compat.Platform.EOL

import java.util.Optional

object ScalacFormat extends (Reporter => ReporterFormat) {
  override def apply(reporter: Reporter): ReporterFormat =
    new ScalacFormat(reporter)
}

/**
 * A format that mimics that of scalac.
 * Adapted from `sbt.LoggerReporter`
 * Copyright 2002-2009 LAMP/EPFL
 * see LICENSE_Scala
 * Original author: Martin Odersky
 */
class ScalacFormat(reporter: Reporter) extends ReporterFormat(reporter) {

  override def formatProblem(problem: Problem): String =
    format(problem.position, problem.message)

  override def printSummary(): Unit = {
    val warnings = reporter.allProblems.count(_.severity == Severity.Warn)
    if (warnings > 0)
      reporter.logger.warn(countElementsAsString(warnings, "warning") + " found")
    val errors = reporter.allProblems.count(_.severity == Severity.Error)
    if (errors > 0)
      reporter.logger.error(countElementsAsString(errors, "error") + " found")
  }

  private def format(pos: Position, msg: String): String = {
    if (!(pos.sourcePath.isPresent || pos.line.isPresent))
      msg
    else {
      val out = new StringBuilder
      val sourcePrefix = jo2o(pos.sourcePath).getOrElse("")
      val columnNumber = jo2o(pos.pointer).map(_.toInt + 1).getOrElse(1)
      val lineNumberString = jo2o(pos.line)
        .map(":" + _ + ":" + columnNumber + ":")
        .getOrElse(":") + " "
      out.append(sourcePrefix + lineNumberString + msg + EOL)
      val lineContent = pos.lineContent
      if (!lineContent.isEmpty) {
        out.append(lineContent + EOL)
        for (space <- jo2o(pos.pointerSpace))
          out.append(space + "^") // pointer to the column position of the error/warning
      }
      out.toString
    }
  }

  private def countElementsAsString(n: Int, elements: String): String = {
    n match {
      case 0 => "no " + elements + "s"
      case 1 => "one " + elements
      case 2 => "two " + elements + "s"
      case 3 => "three " + elements + "s"
      case 4 => "four " + elements + "s"
      case _ => "" + n + " " + elements + "s"
    }
  }

  private def jo2o[T](m: Optional[T]): Option[T] =
    if (m.isPresent) Some(m.get) else None

}
