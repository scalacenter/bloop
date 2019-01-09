package bloop.reporter

import java.io.File

import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.logging.BspServerLogger
import xsbti.Position
import ch.epfl.scala.bsp
import monix.execution.atomic.AtomicLong
import sbt.util.InterfaceUtil
import xsbti.compile.CompileAnalysis

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.Try

final class BspProjectReporter(
    val project: Project,
    override val logger: BspServerLogger,
    override val cwd: AbsolutePath,
    sourcePositionMapper: Position => Position,
    override val config: ReporterConfig,
    reportAllPreviousProblems: Boolean,
    override val _problems: mutable.Buffer[Problem] = mutable.ArrayBuffer.empty
) extends Reporter(logger, cwd, sourcePositionMapper, config, _problems) {
  private val taskId = logger.nextTaskId

  /** A cycle count, initialized to 0 when it's a no-op. */
  private var cycleCount: Int = 0

  /** A thread-safe map with all the compiled files. */
  private val compiledFiles = TrieMap.empty[File, Boolean]

  /** A thread-safe map containing files with reported problems (from this cycle and
   * previous ones, such as buffered warnings from previously compiled source files). */
  private val filesWithProblems = TrieMap.empty[File, Boolean]

  /** Log a problem in a thread-safe manner. */
  override protected def logFull(problem: Problem): Unit = {
    sbt.util.InterfaceUtil.toOption(problem.position.sourceFile()) match {
      case Some(file) =>
        // If it's the first diagnostic for this file, set clear to true
        val clear = filesWithProblems.putIfAbsent(file, true).isEmpty
        logger.diagnostic(project, problem, clear)
      case None => logger.diagnostic(project, problem, false)
    }
  }

  // Report summary manually via `reportEndCompilation` for BSP clients
  override def printSummary(): Unit = ()

  val compileProgressCounter = AtomicLong(0)
  override def reportCompilationProgress(
      progress: Long,
      total: Long,
      phase: String,
      sourceFile: String
  ): Unit = {
    val id = compileProgressCounter.addAndGet(1)
    logger.publishCompileProgress(taskId, progress, total, phase, sourceFile)
  }

  override def reportCancelledCompilation(): Unit = {
    ()
  }

  // Includes problems of both successful and failed compilations
  private var previouslyReportedProblems: List[xsbti.Problem] = Nil
  override def reportStartCompilation(previousProblems: List[xsbti.Problem]): Unit = {
    previouslyReportedProblems = previousProblems
  }

  /** Holds a thunk that reports the end of the previous incremental cycle. It's added by
   * `reportEndIncrementalCycle` and we don't run it eagerly because we need to ensure that
   * all diagnostics (those, for example, coming from previous problemsii and only reported in
   * concrete scenarios) are sent in between task start and task end notifications. This guarantee
   * is violated when we report the end eagerly because:
   *
   *   1. We need to run `reportPreviousProblems` with a value of `reportAllPreviousProblems` at
   *      the very end of compilation (when the last incremental cycle has finished); and
   *   2. There is no way to know if an incremental cycle will be the last one in
   *      `reportEndIncrementalCycle`. We work around this limitation with this approach, so that
   *      when the thunk is run from `reportStartIncrementalCycle` we know a new cycle is coming
   *      and when it's run from `reportEndIncrementalCompilation` we know it's the last cycle.
   */
  private var reportEndPreviousCycleThunk: Boolean => Option[bsp.StatusCode] => Unit =
    (_: Boolean) => (_: Option[bsp.StatusCode]) => ()

  override def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    cycleCount += 1
    reportEndPreviousCycleThunk( /* is the last incremental cycle? */ false)(None)
    val msg = compilationMsgFor(project.name, sources)
    logger.publishCompileStart(project, msg, taskId)
    sources.foreach(sourceFile => compiledFiles.putIfAbsent(sourceFile, true))
  }

  /**
   * Report previous problems according to the BSP specification, with the pertinent
   * handling of diagnostics in non-existing files, diagnostics of files that have
   * already been compiled and non-error diagnostics cached from previous compiler runs.
   *
   * @param reportAllPreviousProblems `true` if called by [[reportEndCompilation()]] in
   *                                  the first compilation of a target via BSP
   */
  private def reportPreviousProblems(reportAllPreviousProblems: Boolean): Unit = {
    val clearedFiles = new mutable.HashSet[File]
    // Process `previouslyReportedProblems` and filter out those that have already been handled
    previouslyReportedProblems = previouslyReportedProblems.filterNot { problem =>
      val markAsReported = InterfaceUtil.toOption(problem.position().sourceFile).map { source =>
        if (!source.exists()) {
          // Clear diagnostics if file doesn't exist anymore
          logger.noDiagnostic(project, source)
          true
        } else if (filesWithProblems.contains(source)) {
          // Do nothing if problem maps to a file with problems, assume it's already reported
          true
        } else if (compiledFiles.contains(source)) {
          // Log no diagnostic if there was a problem in a file that now compiled without problems
          logger.noDiagnostic(project, source)
          true
        } else if (reportAllPreviousProblems) { // `true` in the first compilation via BSP
          // Log all previous problems when target file has not been compiled and it exists
          val clear = !clearedFiles.contains(source)
          logger.diagnostic(project, problem, clear)
          clearedFiles.+=(source)
          true
        } else {
          // It can still be reported in next incremental cycles/end of compilation
          false
        }
      }

      markAsReported.getOrElse(false)
    }
  }

  override def reportEndIncrementalCycle(durationMs: Long, result: Try[Unit]): Unit = {
    val codeRightAfterCycle = result match {
      case scala.util.Success(()) => bsp.StatusCode.Ok
      case scala.util.Failure(_: xsbti.CompileCancelled) => bsp.StatusCode.Cancelled
      case scala.util.Failure(_) => bsp.StatusCode.Error
    }

    // Add a thunk that we will run whenever we know if this is the last cycle or not
    reportEndPreviousCycleThunk = (isLastCycle: Boolean) => {
      (finalCompilationStatusCode: Option[bsp.StatusCode]) => {
        val statusCode = finalCompilationStatusCode.getOrElse(codeRightAfterCycle)
        if (!isLastCycle) reportPreviousProblems(false)
        else reportPreviousProblems(reportAllPreviousProblems)
        logger.publishCompileEnd(project, taskId, allProblems, statusCode)
      }
    }
  }

  override def reportEndCompilation(
      previousAnalysis: Option[CompileAnalysis],
      currentAnalysis: Option[CompileAnalysis],
      code: bsp.StatusCode
  ): Unit = {
    if (cycleCount == 0) {
      // When no-op, we keep reporting the start and the end of compilation for consistency
      logger.publishCompileStart(project, s"Start no-op compilation for ${project.name}", taskId)
      reportPreviousProblems(reportAllPreviousProblems)
      logger.publishCompileEnd(project, taskId, allProblems, code)
    } else {
      // Great, let's report the pending end incremental cycle as the last one
      reportEndPreviousCycleThunk( /* is the last incremental cycle? */ true)(Some(code))
    }

    // Clear the state of files with problems at the end of compilation
    filesWithProblems.clear()
    compiledFiles.clear()
  }
}
