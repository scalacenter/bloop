package bloop.reporter

import java.io.File

import bloop.io.AbsolutePath
import bloop.logging.Logger
import xsbti.{Position, Severity}
import ch.epfl.scala.bsp
import xsbti.compile.CompileAnalysis

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try

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
    val _problems: mutable.Buffer[ProblemPerPhase] = mutable.ArrayBuffer.empty
) extends xsbti.Reporter
    with ConfigurableReporter {

  private var _nextID = 1
  override def reset(): Unit = { _problems.clear(); _nextID = 1; () }
  private def nextID(): Int = { val id = _nextID; _nextID += 1; id }

  override def hasErrors(): Boolean = hasErrors(_problems)
  override def hasWarnings(): Boolean = hasWarnings(_problems)

  override def problems(): Array[xsbti.Problem] = _problems.map(_.problem).toArray
  override def allProblems: Seq[Problem] = _problems.map(p => liftProblem(p.problem)).toList

  def allProblemsPerPhase: Seq[ProblemPerPhase] = _problems.toList

  protected def logFull(problem: Problem): Unit

  protected def liftProblem(p: xsbti.Problem): Problem = {
    p match {
      case p: Problem => p
      case _ =>
        val mappedPos = sourcePositionMapper(p.position)
        val problemID = if (p.position.sourceFile.isPresent) nextID() else -1
        Problem(problemID, p.severity, p.message, mappedPos, p.category)
    }
  }

  protected val phasesAtFile = TrieMap.empty[File, String]
  protected val filesToPhaseStack = TrieMap.empty[File, List[String]]
  override def log(xproblem: xsbti.Problem): Unit = {
    val problem = liftProblem(xproblem)
    val problemPerPhase = sbt.util.InterfaceUtil.toOption(problem.position.sourceFile()) match {
      case Some(file) => ProblemPerPhase(problem, filesToPhaseStack.get(file).flatMap(_.headOption))
      case None => ProblemPerPhase(problem, None)
    }

    _problems += problemPerPhase

    // If we show errors in reverse order, they'll all be shown
    // in `printSummary`.
    if (!config.reverseOrder) {
      logFull(problem)
    }
  }

  /** Report when the compiler enters in a phase. */
  def reportNextPhase(phase: String, sourceFile: File): Unit = {
    // Update the phase that we have for every source file
    val newPhaseStack = phase :: filesToPhaseStack.getOrElse(sourceFile, Nil)
    filesToPhaseStack.update(sourceFile, newPhaseStack)
  }

  override def comment(pos: Position, msg: String): Unit = ()

  private def hasErrors(problems: Seq[ProblemPerPhase]): Boolean =
    problems.exists(_.problem.severity == Severity.Error)

  private def hasWarnings(problems: Seq[ProblemPerPhase]): Boolean =
    problems.exists(_.problem.severity == Severity.Warn)

  /** Report the progress from the compiler. */
  def reportCompilationProgress(progress: Long, total: Long): Unit

  /** Report the compile cancellation of this project. */
  def reportCancelledCompilation(): Unit

  /** A function called *always* at the very beginning of compilation. */
  def reportStartCompilation(previousProblems: List[ProblemPerPhase]): Unit

  /**
   * A function called at the very end of compilation, before returning from Zinc to bloop.
   *
   * This method **is** called if the compilation is a no-op.
   *
   * @param previousAnalysis An instance of a previous compiler analysis, if any.
   * @param analysis An instance of a new compiler analysis, if no error happened.
   * @param code The status code for a given compilation. The status code can be used whenever
   *             there is a noop compile and it's successful or cancelled.
   */
  def reportEndCompilation(
      previousAnalysis: Option[CompileAnalysis],
      analysis: Option[CompileAnalysis],
      code: bsp.StatusCode
  ): Unit = {
    phasesAtFile.clear()
    filesToPhaseStack.clear()
  }

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
   *
   * @param durationMs The time it took to complete the incremental compiler cycle.
   * @param result The result of the incremental cycle. We don't use `bsp.StatusCode` because the
   *               bloop backend, where this method is used, should not depend on bsp4j.
   */
  def reportEndIncrementalCycle(durationMs: Long, result: Try[Unit]): Unit

  /** Create a compilation message summarizing the compilation of `sources` in `projectName`. */
  def compilationMsgFor(projectName: String, sources: Seq[File]): String = {
    import sbt.internal.inc.Analysis
    val (javaSources, scalaSources) = sources.partition(_.getName.endsWith(".java"))
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaSources.size)
    val javaMsg = Analysis.counted("Java source", "", "s", javaSources.size)
    val combined = scalaMsg ++ javaMsg
    combined.mkString(s"Compiling $projectName (", " and ", ")")
  }
}
