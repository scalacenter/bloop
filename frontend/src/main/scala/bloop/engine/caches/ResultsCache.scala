package bloop.engine.caches

import bloop.Project
import bloop.logging.Logger
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

final class ResultsCache(cache: Map[Project, PreviousResult], logger: Logger) {
  import java.util.Optional
  private val EmptyResult: PreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def getResult(project: Project): PreviousResult = cache.getOrElse(project, EmptyResult)
  def addResult(project: Project, previousResult: PreviousResult): ResultsCache =
    new ResultsCache(cache + (project -> previousResult), logger)
  def addResults(ps: List[(Project, PreviousResult)]): ResultsCache =
    new ResultsCache(cache ++ ps, logger)
  def iterator: Iterator[(Project, PreviousResult)] = cache.iterator
  def reset(projects: List[Project]): ResultsCache =
    new ResultsCache(cache ++ projects.map(p => p -> EmptyResult).toMap, logger)

  def initializeResult(project: Project): ResultsCache = {
    import java.nio.file.Files
    import sbt.internal.inc.FileAnalysisStore
    import bloop.util.JavaCompat.EnrichOptional

    def fetchPreviousResult(p: Project): PreviousResult = {
      val analysisFile = project.out.getParent.resolve(s"${project.name}-analysis.bin")
      if (Files.exists(analysisFile.underlying)) {
        logger.debug(s"Loading previous analysis for '${project.name}' from '$analysisFile'.")
        val contents = FileAnalysisStore.binary(analysisFile.toFile).get().toOption
        contents match {
          case Some(res) =>
            PreviousResult.of(Optional.of(res.getAnalysis), Optional.of(res.getMiniSetup))
          case None => EmptyResult
        }
      } else {
        logger.debug(s"No previous analysis for project '${project.name}'")
        EmptyResult
      }
    }

    if (cache.contains(project)) this
    else addResult(project, fetchPreviousResult(project))
  }

  override def toString: String = s"ResultsCache(${cache.mkString(", ")})"
}

object ResultsCache {
  import java.util.concurrent.ConcurrentHashMap
  // TODO: Enrich this with a guava cache that stores maximum 200 analysis file
  private[bloop] val persisted = ConcurrentHashMap.newKeySet[PreviousResult]()
  def getEmpty(logger: Logger): ResultsCache = new ResultsCache(Map.empty, logger)
}
