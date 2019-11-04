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
import bloop.logging.CompilationEvent
import scala.concurrent.Promise

/**
 * A flexible reporter whose configuration is provided by a `ReporterConfig`.
 * This configuration indicated whether to use colors, how to format messages,
 * etc.
 *
 * A reporter has internal state and must be instantiated per compilation.
 *
 * @param logger The logger that will receive the output of the reporter.
 * @param cwd    The current working directory of the user who started compilation.
 * @param config The configuration for this reporter.
 */
abstract class Reporter(
    val logger: Logger,
    override val cwd: AbsolutePath,
    override val config: ReporterConfig,
    val _problems: mutable.Buffer[ProblemPerPhase] = mutable.ArrayBuffer.empty
) extends ZincReporter {
  private case class PositionId(sourcePath: String, offset: Int)
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

  override def hasWarnings(): Boolean = {
    _problems.exists(_.problem.severity == Severity.Warn)
  }

  override def hasErrors(): Boolean = {
    _problems.exists(p => p.problem.severity == Severity.Error)
  }

  override def problems(): Array[xsbti.Problem] = _problems.map(_.problem).toArray
  override def allProblems: Seq[Problem] = _problems.map(p => liftProblem(p.problem)).toList
  override def allProblemsPerPhase: Seq[ProblemPerPhase] = _problems.toList

  private var hasFatalWarningsEnabled: Boolean = false
  override def enableFatalWarnings(): Unit = {
    hasFatalWarningsEnabled = true
  }

  private val sourceFilesWithFatalWarnings = TrieMap.empty[File, Boolean]
  override def getSourceFilesWithFatalWarnings: Set[File] = {
    sourceFilesWithFatalWarnings.keysIterator.toSet
  }

  protected def liftFatalWarning(problem: Problem): Problem = {
    val isFatalWarning = hasFatalWarningsEnabled && problem.severity == Severity.Warn
    if (!isFatalWarning) problem
    else {
      InterfaceUtil
        .toOption(problem.position.sourceFile())
        .foreach(f => sourceFilesWithFatalWarnings.put(f, true))

      problem.copy(severity = Severity.Error)
    }
  }

  private[reporter] def logFull(problem: Problem): Unit

  protected def liftProblem(p: xsbti.Problem): Problem = {
    p match {
      case p: Problem => p
      case _ =>
        val mappedPos = p.position
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
    def processNewPosition(id: PositionId, suppress: Boolean): Boolean = {
      _severities.putIfAbsent(id, problem.severity())
      val old = _messages.getOrElseUpdate(id, List(msg))
      if (old != List(msg)) _messages.update(id, msg :: old)
      suppress
    }

    (InterfaceUtil.toOption(pos.sourcePath()), InterfaceUtil.toOption(pos.offset())) match {
      case (Some(sourcePath), Some(offset)) =>
        val positionId = PositionId(sourcePath, offset)
        _severities.get(positionId) match {
          case Some(xsbti.Severity.Error) => processNewPosition(positionId, true)
          case Some(severity) if severity == problem.severity =>
            val suppress = _messages.getOrElse(positionId, Nil).contains(problem.message())
            processNewPosition(positionId, suppress)
          case Some(severity) =>
            val suppress = (severity, problem.severity) match {
              case (xsbti.Severity.Error, xsbti.Severity.Info) => true
              case (xsbti.Severity.Error, xsbti.Severity.Warn) => true
              case (xsbti.Severity.Warn, xsbti.Severity.Info) => true
              case _ => false
            }
            processNewPosition(positionId, suppress)
          case _ => processNewPosition(positionId, false)
        }
      case _ => false
    }
  }

  override def log(xproblem: xsbti.Problem): Unit = {
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

  override def comment(pos: Position, msg: String): Unit = ()

  /** Report when the compiler enters in a phase. */
  override def reportNextPhase(phase: String, sourceFile: File): Unit = {
    // Update the phase that we have for every source file
    val newPhaseStack = phase :: filesToPhaseStack.getOrElse(sourceFile, Nil)
    filesToPhaseStack.update(sourceFile, newPhaseStack)
  }

  override def processEndCompilation(
      previousSuccessfulProblems: List[ProblemPerPhase],
      code: bsp.StatusCode,
      clientClassesDir: Option[AbsolutePath],
      analysisOut: Option[AbsolutePath]
  ): Unit = {
    // Clean some state, but return no end of compilation
    phasesAtFile.clear()
    filesToPhaseStack.clear()
  }
}

trait ZincReporter extends xsbti.Reporter with ConfigurableReporter {
  def allProblemsPerPhase: Seq[ProblemPerPhase]

  /**
   * Enables internal bloop implementation of fatal warnings.
   *
   * Having fatal warnings implemented inside the compiler has several
   * drawbacks:
   *
   * 1. Bloop receives a failed compilation when there is a warning. A failed
   * compilation doesn't allow us to cache the work done during an incremental
   * compiler cycle and the compilation products generated are thrown away.
   * This means next time we compile we need to redo the work of compiling all
   * changed source files when we would only want to recompile the files that
   * produced a fatal warning.
   *
   * 2. When warnings are produced, Metals doesn't have access to the class
   * files generated by the failed compilation. This affects negatively the
   * editing experience in the editor.
   *
   * To work around these limitations, we remove `-Xfatal-warnings` from the
   * scalac options passed to the compiler and we implement fatal warnings
   * ourselves in our reporter implementation.
   *
   * Our implementation promotes warnings to errors just before we're going to
   * report them but still represents warnings as warnings in the analysis
   * file. At the same time, it also treats successful compilations with fatal
   * warnings as errors at the very end of the compilation pipeline, after all
   * the compilation passes have been done in a build graph, and guarantees
   * that it works for clients that have their compilations deduplicated.
   *
   * All in all, implementing fatal warnings reproduces the previous behavior
   * in the compiler but without the inconveniences of handling compilation
   * results as failures when only fatal warnings are solely responsible for
   * the failed result.
   */
  def enableFatalWarnings(): Unit

  /**
   * Tells the caller which source files have fatal warnings so that Bloop
   * can mark them as changed and get recompiled in the next incremental cycle.
   */
  def getSourceFilesWithFatalWarnings: Set[File]

  /** Report the progress from the compiler. */
  def reportCompilationProgress(progress: Long, total: Long): Unit

  /** Report the compile cancellation of this project. */
  def reportCancelledCompilation(): Unit

  /** A function called *always* at the very beginning of compilation. */
  def reportStartCompilation(previousProblems: List[ProblemPerPhase]): Unit

  /**
   * A function called at the very end of compilation that processes the end of
   * compilation from the reporter point of view as well as announce the end to
   * the client.
   *
   * This method is called when the caller knows for sure that announcing the
   * end of compilation to the client is safe. For example, when a compilation
   * fails and there are no background tasks to run. Compare this to the sister
   * `reportEndCompilation` method which is used for the opposite.
   *
   * @param previousSuccessfulProblems The problems reported in the previous
   *                                   compiler analysis that were successful.
   * @param code The status code for a given compilation. The status code can be used whenever
   *             there is a noop compile and it's successful or cancelled.
   */
  def reportEndCompilation(): Unit

  /**
   * A function called at the very end of compilation that processes the state
   * of the reporter. The execution of this endpoint can clean or report more
   * diagnostics with the client but it does not announce the end of
   * compilation, which is instead done with [[announceEndCompilation]].
   *
   * @param previousSuccessfulProblems The problems reported in the previous
   *    compiler analysis that were successful.
   * @param code The status code for a given compilation. The status code can
   *    be used whenever there is a noop compile and it's successful or cancelled.
   * @param clientClassesDir The classes directory of the client whose
   *    compilation has been finished **in case** it's already populated.
   * @param clientClassesDir The analysis file of the client in case it has been rewritten.
   *    compilation has been finished. When the request is deduplicated, this
   *    directory matches the classes directory of the deduplicated client.
   */
  def processEndCompilation(
      previousSuccessfulProblems: List[ProblemPerPhase],
      code: bsp.StatusCode,
      clientClassesDir: Option[AbsolutePath],
      analysisOut: Option[AbsolutePath]
  ): Unit

  /**
   * A function called before every incremental cycle with the compilation
   * inputs. This method is not called if the compilation is a no-op (e.g. same
   * analysis as before).
   */
  def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit

  /** Report when the compiler enters in a phase. */
  def reportNextPhase(phase: String, sourceFile: File): Unit

  /**
   * A function called after every incremental cycle, even if any compilation
   * errors happen. This method is not called if the compilation is a no-op
   * (e.g. same analysis as before).
   *
   * @param durationMs The time it took to complete the incremental compiler cycle.
   * @param result The result of the incremental cycle. We don't use `bsp.StatusCode` because the
   *               bloop backend, where this method is used, should not depend on bsp4j.
   */
  def reportEndIncrementalCycle(durationMs: Long, result: Try[Unit]): Unit
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
