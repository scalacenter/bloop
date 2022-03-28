package bloop.util

import bloop.reporter.ProblemPerPhase

import xsbti.VirtualFileRef
import xsbti.compile.CompileAnalysis
import xsbti.compile.analysis.SourceInfo

object AnalysisUtils {
  import scala.collection.JavaConverters._
  def sourceInfosFrom(previousAnalysis: CompileAnalysis): Map[VirtualFileRef, SourceInfo] = {
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
