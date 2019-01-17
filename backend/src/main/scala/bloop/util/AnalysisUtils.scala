package bloop.util

import java.io.File

import bloop.reporter.ProblemPerPhase
import xsbti.compile.CompileAnalysis
import xsbti.compile.analysis.SourceInfo

object AnalysisUtils {
  import scala.collection.JavaConverters._
  def sourceInfosFrom(previousAnalysis: CompileAnalysis): Map[File, SourceInfo] = {
    previousAnalysis.readSourceInfos().getAllSourceInfos.asScala.toMap
  }

  def problemsFrom(previousAnalysis: CompileAnalysis): List[ProblemPerPhase] = {
    val infos = sourceInfosFrom(previousAnalysis).values
    infos.iterator
      .flatMap(info => info.getReportedProblems.toList)
      .map(p => ProblemPerPhase(p, None))
      .toList
  }
}
