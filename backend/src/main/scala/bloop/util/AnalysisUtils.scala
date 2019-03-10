package bloop.util

import bloop.reporter.ProblemPerPhase
import xsbti.compile.CompileAnalysis

object AnalysisUtils {
  def problemsFrom(previousAnalysis: Option[CompileAnalysis]): List[ProblemPerPhase] = {
    import scala.collection.JavaConverters._
    val infos =
      previousAnalysis.toList.flatMap(_.readSourceInfos().getAllSourceInfos.asScala.values)
    infos.iterator
      .flatMap(info => info.getReportedProblems.toList)
      .map(p => ProblemPerPhase(p, None))
      .toList
  }
}
