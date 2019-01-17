package bloop.reporter

import java.io.File

import bloop.io.AbsolutePath
import bloop.logging.{Logger, ObservedLogger}
import xsbti.{Position, Severity}
import ch.epfl.scala.bsp
import sbt.util.InterfaceUtil
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
    val logger: ObservedLogger[Logger],
    val cwd: AbsolutePath,
    sourcePositionMapper: Position => Position,
    val config: ReporterConfig,
    val _problems: mutable.Buffer[ProblemPerPhase] = mutable.ArrayBuffer.empty
) extends xsbti.Reporter
    with ConfigurableReporter {
  case class PositionId(sourcePath: String, offset: Int)
  private val _severities = TrieMap.empty[PositionId, Severity]
  private val _messages = TrieMap.empty[PositionId, List[String]]

  private var _nextID = 1
  private def nextID(): Int = { val id = _nextID; _nextID += 1; id }
  override def reset(): Unit = {
    _problems.clear()
    _severities.clear()
    _messages.clear()
    _nextID = 1
    ()
  }

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

  // Adapted from https://github.com/scala/scala/blob/2.12.x/src/compiler/scala/tools/nsc/reporters/AbstractReporter.scala#L68-L88
  private def deduplicate(problem: xsbti.Problem): Boolean = {
    val pos = problem.position
    val msg = problem.message()
    def processNewPosition(id: PositionId, supress: Boolean): Boolean = {
      _severities.putIfAbsent(id, problem.severity())
      val old = _messages.getOrElseUpdate(id, List(msg))
      if (old != List(msg)) _messages.update(id, msg :: old)
      supress
    }

    (InterfaceUtil.toOption(pos.sourcePath()), InterfaceUtil.toOption(pos.offset())) match {
      case (Some(sourcePath), Some(offset)) =>
        val positionId = PositionId(sourcePath, offset)
        _severities.get(positionId) match {
          case Some(xsbti.Severity.Error) => processNewPosition(positionId, true)
          case Some(severity) if severity == problem.severity =>
            val supress = _messages.getOrElse(positionId, Nil).contains(problem.message())
            processNewPosition(positionId, supress)
          case Some(severity) =>
            val supress = (severity, problem.severity) match {
              case (xsbti.Severity.Error, xsbti.Severity.Info) => true
              case (xsbti.Severity.Error, xsbti.Severity.Warn) => true
              case (xsbti.Severity.Warn, xsbti.Severity.Info) => true
              case _ => false
            }
            processNewPosition(positionId, supress)
          case _ => processNewPosition(positionId, false)
        }
      case _ => false
    }
  }

  override def log(xproblem: xsbti.Problem): Unit = {
    registerAction(ReporterAction.ReportProblem(xproblem))
    if (deduplicate(xproblem)) ()
    else {
      val problem = liftProblem(xproblem)
      val problemPerPhase = InterfaceUtil.toOption(problem.position.sourceFile()) match {
        case Some(file) =>
          ProblemPerPhase(problem, filesToPhaseStack.get(file).flatMap(_.headOption))
        case None => ProblemPerPhase(problem, None)
      }

      _problems += problemPerPhase

      // If we show errors in reverse order, they'll all be shown
      // in `printSummary`.
      if (!config.reverseOrder) {
        logFull(problem)
      }
    }
  }

  /** Report when the compiler enters in a phase. */
  def reportNextPhase(phase: String, sourceFile: File): Unit = {
    // Update the phase that we have for every source file
    val newPhaseStack = phase :: filesToPhaseStack.getOrElse(sourceFile, Nil)
    filesToPhaseStack.update(sourceFile, newPhaseStack)
    registerAction(ReporterAction.ReportNextPhase(phase, sourceFile))
  }

  override def comment(pos: Position, msg: String): Unit = ()

  private def hasErrors(problems: Seq[ProblemPerPhase]): Boolean =
    problems.exists(_.problem.severity == Severity.Error)

  private def hasWarnings(problems: Seq[ProblemPerPhase]): Boolean =
    problems.exists(_.problem.severity == Severity.Warn)

  private def registerAction(action: ReporterAction): Unit = {
    logger.observer.onNext(Left(action))
    ()
  }

  /** Report the progress from the compiler. */
  def reportCompilationProgress(progress: Long, total: Long): Unit = {
    registerAction(ReporterAction.ReportCompilationProgress(progress, total))
  }

  /** Report the compile cancellation of this project. */
  def reportCancelledCompilation(): Unit = {
    registerAction(ReporterAction.ReportCancelledCompilation)
  }

  /** A function called *always* at the very beginning of compilation. */
  def reportStartCompilation(previousProblems: List[ProblemPerPhase]): Unit = {
    registerAction(ReporterAction.ReportStartCompilation(previousProblems))
  }

  /**
   * A function called at the very end of compilation, before returning from
   * Zinc to bloop. This method **is** called if the compilation is a no-op.
   *
   * @param previousProblems The problems reported in the previous compiler analysis.
   * @param analysis An instance of a new compiler analysis, if no error happened.
   * @param code The status code for a given compilation. The status code can be used whenever
   *             there is a noop compile and it's successful or cancelled.
   */
  def reportEndCompilation(
      previousSuccessfulProblems: List[ProblemPerPhase],
      code: bsp.StatusCode
  ): Unit = {
    registerAction(ReporterAction.ReportEndCompilation(previousSuccessfulProblems, code))
    phasesAtFile.clear()
    filesToPhaseStack.clear()
  }

  /**
   * A function called before every incremental cycle with the compilation
   * inputs. This method is not called if the compilation is a no-op (e.g. same
   * analysis as before).
   */
  def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    registerAction(ReporterAction.ReportStartIncrementalCycle(sources, outputDirs))
  }

  /**
   * A function called after every incremental cycle, even if any compilation
   * errors happen. This method is not called if the compilation is a no-op
   * (e.g. same analysis as before).
   *
   * @param durationMs The time it took to complete the incremental compiler cycle.
   * @param result The result of the incremental cycle. We don't use `bsp.StatusCode` because the
   *               bloop backend, where this method is used, should not depend on bsp4j.
   */
  def reportEndIncrementalCycle(durationMs: Long, result: Try[Unit]): Unit = {
    registerAction(ReporterAction.ReportEndIncrementalCycle(durationMs, result))
  }

  /**
   * Replay a reporter action in this very implementation of the reporter.
   *
   * This method is one of the main ways we achieve mirror compilation side
   * effects for deduplicated compile requests. Regardless of the origin reporter,
   * a reporter will always register actions that can then be replayed in other
   * reporter implementations. The same is achieved at a higher-level in
   * `ObservableLogger` that registers high-level logger actions.
   */
  def replay(action: ReporterAction): Unit = {
    action match {
      case a: ReporterAction.ReportStartCompilation =>
        reportStartCompilation(a.previousProblems)
      case a: ReporterAction.ReportStartIncrementalCycle =>
        reportStartIncrementalCycle(a.sources, a.outputDirs)
      case a: ReporterAction.ReportProblem => log(a.problem)
      case a: ReporterAction.ReportNextPhase => reportNextPhase(a.phase, a.sourceFile)
      case a: ReporterAction.ReportCompilationProgress =>
        reportCompilationProgress(a.progress, a.total)
      case a: ReporterAction.ReportEndIncrementalCycle =>
        reportEndIncrementalCycle(a.durationMs, a.result)
      case ReporterAction.ReportCancelledCompilation => reportCancelledCompilation()
      case a: ReporterAction.ReportEndCompilation =>
        reportEndCompilation(a.previousSuccessfulProblems, a.code)
    }
  }
}

object Reporter {

  /** Create a compilation message summarizing the compilation of `sources` in `projectName`. */
  def compilationMsgFor(projectName: String, sources: Seq[File]): String = {
    import sbt.internal.inc.Analysis
    val (javaSources, scalaSources) = sources.partition(_.getName.endsWith(".java"))
    val scalaMsg = Analysis.counted("Scala source", "", "s", scalaSources.size)
    val javaMsg = Analysis.counted("Java source", "", "s", javaSources.size)
    val combined = scalaMsg ++ javaMsg
    combined.mkString(s"Compiling $projectName (", " and ", ")")
  }

  /** Groups problems per file where they originated. */
  def groupProblemsByFile(ps: List[ProblemPerPhase]): Map[File, List[ProblemPerPhase]] = {
    val problemsPerFile = mutable.HashMap[File, List[ProblemPerPhase]]()
    ps.foreach {
      case pp @ ProblemPerPhase(p, phase) =>
        InterfaceUtil.toOption(p.position().sourceFile).foreach { file =>
          val newProblemsPerFile = pp :: problemsPerFile.getOrElse(file, Nil)
          problemsPerFile.+=(file -> newProblemsPerFile)
        }
    }
    problemsPerFile.toMap
  }
}
