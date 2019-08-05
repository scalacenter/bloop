package bloop.reporter

import java.io.File
import scala.util.Try
import ch.epfl.scala.bsp

sealed trait ReporterAction
object ReporterAction {
  final case object EnableFatalWarnings extends ReporterAction
  final case object ReportStartCompilation extends ReporterAction

  final case class ReportStartIncrementalCycle(
      sources: Seq[File],
      outputDirs: Seq[File]
  ) extends ReporterAction

  final case class ReportProblem(
      problem: xsbti.Problem
  ) extends ReporterAction

  final case object PublishDiagnosticsSummary extends ReporterAction

  final case class ReportNextPhase(
      phase: String,
      sourceFile: File
  ) extends ReporterAction

  final case class ReportCompilationProgress(
      progress: Long,
      total: Long
  ) extends ReporterAction

  final case class ReportEndIncrementalCycle(
      durationMs: Long,
      result: Try[Unit]
  ) extends ReporterAction

  final case object ReportCancelledCompilation extends ReporterAction

  final case class ReportEndCompilation(code: bsp.StatusCode) extends ReporterAction
}
