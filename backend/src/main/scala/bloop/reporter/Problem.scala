package bloop.reporter

import xsbti.Severity

/** Describes a problem (error, warning, message, etc.) given to the reporter. */
final case class Problem private (
    /** A unique (per compilation run) number for this message. -1 means unknown. */
    id: Int,
    /** The severity of this message. */
    severity: xsbti.Severity,
    /** The actual content of the message */
    message: String,
    /** Position in the source code where the message was triggered */
    position: xsbti.Position,
    /** The category of this problem. */
    category: String
) extends xsbti.Problem

object Problem {
  def fromZincProblem(problem: xsbti.Problem): Problem = {
    Problem(-1, problem.severity(), problem.message(), problem.position(), problem.category())
  }

  case class DiagnosticsCount(errors: Long, warnings: Long) {
    override def toString: String = s"$errors errors, $warnings warnings"
  }

  def count(problems: Array[xsbti.Problem]): DiagnosticsCount = {
    // Compute the count manually because `count` returns an `Int`, not a `Long`
    var errors = 0L
    var warnings = 0L
    problems.foreach { p =>
      val severity = p.severity()
      if (severity == Severity.Error) errors += 1
      if (severity == Severity.Warn) warnings += 1
    }

    DiagnosticsCount(errors, warnings)
  }
}
