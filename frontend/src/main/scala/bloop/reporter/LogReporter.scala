package bloop.reporter
import java.io.File

import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.logging.{Logger, ObservedLogger}

import ch.epfl.scala.bsp

import xsbti.compile.CompileAnalysis
import xsbti.{Position, Severity}
import sbt.util.InterfaceUtil

import scala.collection.mutable
import scala.collection.concurrent.TrieMap
import bloop.logging.CompilationEvent

final class LogReporter(
    val project: Project,
    override val logger: Logger,
    cwd: AbsolutePath,
    config: ReporterConfig,
    override val _problems: mutable.Buffer[ProblemPerPhase] = mutable.ArrayBuffer.empty
) extends Reporter(logger, cwd, config, _problems) {

  // Contains the files that are compiled in all incremental compiler cycles
  private val compilingFiles = mutable.HashSet[File]()

  private final val format = config.format(this)
  override def printSummary(): Unit = {
    if (config.reverseOrder) {
      _problems.reverse.foreach(p => logFull(liftProblem(p.problem)))
    }

    format.printSummary()
  }

  /**
   * Log the full error message for `problem`.
   *
   * @param problem The problem to log.
   */
  override private[reporter] def logFull(problem0: Problem): Unit = {
    val problem = super.liftFatalWarning(problem0)
    val text = format.formatProblem(problem)
    problem.severity match {
      case Severity.Error => logger.error(text)
      case Severity.Warn => logger.warn(text)
      case Severity.Info => logger.info(text)
    }
  }

  override def reportCompilationProgress(progress: Long, total: Long): Unit = {}

  override def reportCancelledCompilation(): Unit = {
    logger.warn(s"Cancelling compilation of ${project.name}")
    ()
  }

  override def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    // TODO(jvican): Fix https://github.com/scalacenter/bloop/issues/386 here
    require(sources.size > 0) // This is an invariant enforced in the call-site
    compilingFiles ++= sources
    logger.info(Reporter.compilationMsgFor(project.name, sources))
  }

  override def reportEndIncrementalCycle(durationMs: Long, result: scala.util.Try[Unit]): Unit = {
    logger.info(s"Compiled ${project.name} (${durationMs}ms)")
  }

  override def reportStartCompilation(previousProblems: List[ProblemPerPhase]): Unit = ()

  override def reportEndCompilation(): Unit = ()

  override def processEndCompilation(
      previousSuccessfulProblems: List[ProblemPerPhase],
      code: bsp.StatusCode,
      clientClassesDir: Option[AbsolutePath],
      analysisOut: Option[AbsolutePath]
  ): Unit = {
    code match {
      case bsp.StatusCode.Ok =>
        val eligibleProblemsPerFile = Reporter
          .groupProblemsByFile(previousSuccessfulProblems)
          .filterKeys(f => !compilingFiles.contains(f))
          .valuesIterator
        val warningsFromPreviousRuns = eligibleProblemsPerFile
          .flatMap(_.filter(_.problem.severity() == xsbti.Severity.Warn))
          .toList

        // Note that buffered warnings are not added back to the current analysis on purpose
        warningsFromPreviousRuns.foreach(p => log(p.problem))
      case _ => ()
    }

    // Create a dummy end compilation that we return but contains useless data
    super.processEndCompilation(previousSuccessfulProblems, code, clientClassesDir, analysisOut)
  }
}
