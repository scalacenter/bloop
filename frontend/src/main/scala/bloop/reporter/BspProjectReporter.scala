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

  /** A thread-safe map containing files with reported problems (from this cycle and
   * previous ones, such as buffered warnings from previously compiled source files). */
  private val filesWithProblems = TrieMap.empty[File, Boolean]
  private val compiledFiles = TrieMap.empty[File, Boolean]

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
    logger.publishCompileStart(project, taskId)
  }

  override def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    sources.foreach { sourceFile =>
      compiledFiles.putIfAbsent(sourceFile, true)
    }
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

  override def reportEndIncrementalCycle(durationMs: Long): Unit = {
    reportPreviousProblems(false)
  }

  override def reportEndCompilation(
      previousAnalysis: Option[CompileAnalysis],
      currentAnalysis: Option[CompileAnalysis],
      code: bsp.StatusCode
  ): Unit = {
    reportPreviousProblems(reportAllPreviousProblems)

    // Clear the state of files with problems at the end of compilation
    filesWithProblems.clear()
    compiledFiles.clear()
    logger.publishCompileEnd(project, taskId, allProblems, code)
  }
}
