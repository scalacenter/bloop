package bloop.reporter

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
}
