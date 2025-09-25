package bloop.reporter

import java.util.Optional

import xsbti.Severity
import java.util.ArrayList
import java.io.File
import java.nio.file.Paths

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
    category: String,
    /** Unique code attatched to the diagnostic being reported */
    override val diagnosticCode: Optional[xsbti.DiagnosticCode],
    override val diagnosticRelatedInforamation: java.util.List[xsbti.DiagnosticRelatedInformation],
    override val actions: java.util.List[xsbti.Action]
) extends xsbti.Problem

object Problem {
  def fromZincProblem(problem: xsbti.Problem): Problem = {
    Problem(
      -1,
      problem.severity(),
      problem.message(),
      problem.position(),
      problem.category(),
      problem.diagnosticCode(),
      problem.diagnosticRelatedInformation(),
      problem.actions()
    )
  }

  def fromError(error: Throwable): Option[Problem] = {
    val assertionErrorRegex = """tree position: line (\d+) of (.+\.scala)""".r
    assertionErrorRegex.findFirstMatchIn(error.getMessage()) match {
      case Some(m) =>
        val line = m.group(1).toInt
        val file = Paths.get(m.group(2)).toFile()
        Some(
          Problem(
            -1,
            xsbti.Severity.Error,
            error.getMessage(),
            BloopPosition(file, Optional.of(line)),
            "assertion error",
            Optional.empty(),
            new ArrayList[xsbti.DiagnosticRelatedInformation](),
            new ArrayList[xsbti.Action]()
          )
        )
      case None => // No match
        None
    }
  }

  case class BloopPosition(file: File, line: Optional[Integer]) extends xsbti.Position {

    override def lineContent(): String = ""

    override def offset(): Optional[Integer] = Optional.empty()

    override def pointer(): Optional[Integer] = Optional.empty()

    override def pointerSpace(): Optional[String] = Optional.empty()

    override def sourcePath(): Optional[String] = Optional.empty()

    override def sourceFile(): Optional[File] = Optional.of(file)

  }

  case class DiagnosticsCount(errors: Long, warnings: Long) {
    override def toString: String = s"$errors errors, $warnings warnings"
  }

  def count(problems: List[ProblemPerPhase]): DiagnosticsCount = {
    // Compute the count manually because `count` returns an `Int`, not a `Long`
    var errors = 0L
    var warnings = 0L
    problems.foreach { p =>
      val severity = p.problem.severity()
      if (severity == Severity.Error) errors += 1
      if (severity == Severity.Warn) warnings += 1
    }

    DiagnosticsCount(errors, warnings)
  }
}
