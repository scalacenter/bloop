package bloop.engine.caches

import java.util.Optional

import bloop.{Compiler, Project}
import bloop.Compiler.Result
import bloop.engine.Build
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.reporter.Reporter
import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

/**
 * Maps projects to compilation results, populated by `Tasks.compile`.
 *
 * The results cache has two important goals:
 *   1. Keep track of the last compilation results, no matter what results those were.
 *   2. Keep track of the last successful incremental results per project, so that they
 *      can be used by `compile` to locate the last good analysis file.
 *
 * This data structure is not thread-safe and should not be used like so.
 *
 * @param all The map of projects to latest compilation results.
 * @param succesful The map of all projects to latest successful compilation results.
 * @param logger A logger.
 */
final class ResultsCache private (
    all: Map[Project, Compiler.Result],
    successful: Map[Project, PreviousResult],
    logger: Logger
) {
  /** Returns the last succesful result if present, empty otherwise. */
  def lastSuccessfulResult(project: Project): PreviousResult =
    successful.getOrElse(project, ResultsCache.EmptyResult)

  /** Returns the latest compilation result if present, empty otherwise. */
  def latestResult(project: Project): Compiler.Result =
    all.getOrElse(project, Result.Empty)

  def allSuccessful: Iterator[(Project, PreviousResult)] = successful.iterator
  def cleanSuccessful(projects: List[Project]): ResultsCache = {
    // Remove all the successful results from the cache.
    val newSuccessful = successful.filterKeys(p => !projects.contains(p))
    new ResultsCache(all, newSuccessful, logger)
  }

  def addResult(project: Project, result: Compiler.Result): ResultsCache = {
    val newAll = all + (project -> result)
    result match {
      case s: Compiler.Result.Success =>
        new ResultsCache(newAll, successful + (project -> s.previous), logger)
      case r => new ResultsCache(newAll, successful, logger)
    }
  }

  def addResults(ps: List[(Project, Compiler.Result)]): ResultsCache =
    ps.foldLeft(this) { case (rs, (p, r)) => rs.addResult(p, r) }

  private def initializeResult(project: Project, cwd: AbsolutePath): ResultsCache = {
    import java.nio.file.Files
    import sbt.internal.inc.FileAnalysisStore
    import bloop.util.JavaCompat.EnrichOptional

    def fetchPreviousResult(p: Project): Compiler.Result = {
      val analysisFile = project.out.getParent.resolve(s"${project.name}-analysis.bin")
      if (Files.exists(analysisFile.underlying)) {
        val contents = FileAnalysisStore.binary(analysisFile.toFile).get().toOption
        contents match {
          case Some(res) =>
            logger.debug(s"Loading previous analysis for '${project.name}' from '$analysisFile'.")
            val p = PreviousResult.of(Optional.of(res.getAnalysis), Optional.of(res.getMiniSetup))
            val reporter = Reporter.fromAnalysis(res.getAnalysis, cwd, logger)
            Result.Success(reporter, p)
          case None =>
            logger.debug(s"Analysis '$analysisFile' for '${project.name}' is empty.")
            Result.Empty
        }
      } else {
        logger.debug(s"Missing analysis file for project '${project.name}'")
        Result.Empty
      }
    }

    if (all.contains(project)) this
    else addResult(project, fetchPreviousResult(project))
  }

  override def toString: String = s"ResultsCache(${successful.mkString(", ")})"
}

object ResultsCache {
  import java.util.concurrent.ConcurrentHashMap

  // TODO: Use a guava cache that stores maximum 200 analysis file
  private[bloop] val persisted = ConcurrentHashMap.newKeySet[PreviousResult]()

  private[ResultsCache] final val EmptyResult: PreviousResult =
    PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup])

  def load(build: Build, cwd: AbsolutePath, logger: Logger): ResultsCache = {
    build.projects.foldLeft(new ResultsCache(Map.empty, Map.empty, logger)) {
      case (results, project) => results.initializeResult(project, cwd)
    }
  }
}
