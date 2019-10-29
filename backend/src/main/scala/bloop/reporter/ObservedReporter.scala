package bloop.reporter

import java.io.File
import scala.util.Try
import scala.collection.mutable
import bloop.io.AbsolutePath
import ch.epfl.scala.bsp
import bloop.logging.{ObservedLogger, Logger}
import scala.concurrent.Promise
import bloop.logging.CompilationEvent

final class ObservedReporter(
    val observedLogger: ObservedLogger[Logger],
    val underlying: Reporter
) extends ZincReporter {
  override def log(xproblem: xsbti.Problem): Unit = {
    underlying.log(xproblem)
    registerAction(ReporterAction.ReportProblem(xproblem))
  }

  override val cwd: AbsolutePath = underlying.cwd
  override val config: ReporterConfig = underlying.config
  override def allProblems: Seq[Problem] = underlying.allProblems
  override def problems: Array[xsbti.Problem] = underlying.problems
  override def hasErrors: Boolean = underlying.hasErrors
  override def hasWarnings: Boolean = underlying.hasWarnings
  override def comment(pos: xsbti.Position, msg: String): Unit = underlying.comment(pos, msg)
  override def reset(): Unit = underlying.reset()
  override def enableFatalWarnings(): Unit = {
    underlying.enableFatalWarnings()
    registerAction(ReporterAction.EnableFatalWarnings)
  }

  override def getSourceFilesWithFatalWarnings: Set[File] =
    underlying.getSourceFilesWithFatalWarnings

  override def printSummary(): Unit = {
    underlying.printSummary()
    registerAction(ReporterAction.PublishDiagnosticsSummary)
  }

  override def reportCompilationProgress(progress: Long, total: Long): Unit = {
    underlying.reportCompilationProgress(progress, total)
    registerAction(ReporterAction.ReportCompilationProgress(progress, total))
  }

  override def reportCancelledCompilation(): Unit = {
    underlying.reportCancelledCompilation()
    registerAction(ReporterAction.ReportCancelledCompilation)
  }

  override def reportStartCompilation(previousProblems: List[ProblemPerPhase]): Unit = {
    underlying.reportStartCompilation(previousProblems)
    registerAction(ReporterAction.ReportStartCompilation)
  }

  override def reportEndCompilation(): Unit = {
    underlying.reportEndCompilation()
  }

  override def processEndCompilation(
      previousSuccessfulProblems: List[ProblemPerPhase],
      code: bsp.StatusCode,
      clientClassesDir: Option[AbsolutePath],
      analysisOut: Option[AbsolutePath]
  ): Unit = {
    underlying.processEndCompilation(
      previousSuccessfulProblems,
      code,
      clientClassesDir,
      analysisOut
    )
    registerAction(ReporterAction.ProcessEndCompilation(code))
  }

  override def reportStartIncrementalCycle(sources: Seq[File], outputDirs: Seq[File]): Unit = {
    underlying.reportStartIncrementalCycle(sources, outputDirs)
    registerAction(ReporterAction.ReportStartIncrementalCycle(sources, outputDirs))
  }

  override def reportNextPhase(phase: String, sourceFile: File): Unit = {
    underlying.reportNextPhase(phase, sourceFile)
    registerAction(ReporterAction.ReportNextPhase(phase, sourceFile))
  }

  override def reportEndIncrementalCycle(durationMs: Long, result: Try[Unit]): Unit = {
    underlying.reportEndIncrementalCycle(durationMs, result)
    registerAction(ReporterAction.ReportEndIncrementalCycle(durationMs, result))
  }

  override def allProblemsPerPhase: Seq[ProblemPerPhase] = underlying.allProblemsPerPhase

  private def registerAction(action: ReporterAction): Unit = {
    observedLogger.observer.onNext(Left(action))
    ()
  }
}
