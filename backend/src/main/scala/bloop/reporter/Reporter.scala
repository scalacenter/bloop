package bloop.reporter

import java.io.File

import bloop.io.AbsolutePath
import bloop.logging.Logger
import xsbti.{Position, Severity}
import ch.epfl.scala.bsp
import xsbti.compile.CompileAnalysis

import scala.collection.mutable

/**
 * A flexible reporter whose configuration is provided by a `ReporterConfig`.
 * This configuration indicated whether to use colors, how to format messages,
 * etc.
 *
 * A reporter has internal state and must be instantiated per compilation.
 *
 * @param logger The logger that will receive the output of the reporter.
 * @param cwd    The current working directory of the user who started compilation.
 * @param sourcePositionMapper A function that transforms positions.
 * @param config The configuration for this reporter.
 */
abstract class Reporter(
    val logger: Logger,
    val cwd: AbsolutePath,
    sourcePositionMapper: Position => Position,
    val config: ReporterConfig,
    val _problems: mutable.Buffer[Problem] = mutable.ArrayBuffer.empty
) extends xsbti.Reporter
    with ConfigurableReporter {

  private var _nextID = 1
  override def reset(): Unit = { _problems.clear(); _nextID = 1; () }
  private def nextID(): Int = { val id = _nextID; _nextID += 1; id }

  override def hasErrors(): Boolean = hasErrors(_problems)
  override def hasWarnings(): Boolean = hasWarnings(_problems)

  override def allProblems: Seq[Problem] = _problems
  override def problems(): Array[xsbti.Problem] = _problems.toArray

  protected def logFull(problem: Problem): Unit

  override def log(prob: xsbti.Problem): Unit = {
    val mappedPos = sourcePositionMapper(prob.position)
    val problemID = if (prob.position.sourceFile.isPresent) nextID() else -1
    val problem =
      Problem(problemID, prob.severity, prob.message, mappedPos, prob.category)
    _problems += problem

    // If we show errors in reverse order, they'll all be shown
    // in `printSummary`.
    if (!config.reverseOrder) {
      logFull(problem)
    }
  }

  override def comment(pos: Position, msg: String): Unit = ()

  private def hasErrors(problems: Seq[Problem]): Boolean =
    problems.exists(_.severity == Severity.Error)

  private def hasWarnings(problems: Seq[Problem]): Boolean =
    problems.exists(_.severity == Severity.Warn)

  /** A function called *always* at the very beginning of compilation. */
  def reportStartCompilation(previousProblems: List[xsbti.Problem]): Unit

  /**
   * A function called at the very end of compilation, before returning from Zinc to bloop.
   *
   * This method **is** called if the compilation is a no-op.
   *
   * @param previousAnalysis An instance of a previous compiler analysis, if any.
   * @param analysis An instance of a new compiler analysis, if no error happened.
   * @param code The status code for a given compilation.
   */
  def reportEndCompilation(
      previousAnalysis: Option[CompileAnalysis],
      analysis: Option[CompileAnalysis],
      code: bsp.StatusCode
  ): Unit

  /**
   * A function called before every incremental cycle with the compilation inputs.
   *
   * This method is not called if the compilation is a no-op (e.g. same analysis as before).
   */
  def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit

  /**
   * A function called after every incremental cycle, even if any compilation errors happen.
   *
   * This method is not called if the compilation is a no-op (e.g. same analysis as before).
   */
  def reportEndIncrementalCycle(): Unit
}
