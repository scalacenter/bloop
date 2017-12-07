package bloop.engine

import bloop.CompilerCache
import bloop.cli.ExitStatus
import bloop.engine.caches.{ResultsCache, StateCache}
import bloop.io.Paths
import bloop.logging.Logger

final case class State private (
    build: Build,
    results: ResultsCache,
    status: ExitStatus,
    logger: Logger
) {
  private[bloop] val executionContext: scala.concurrent.ExecutionContext =
    ExecutionContext.threadPool
  def mergeStatus(newStatus: ExitStatus): State =
    this.copy(status = ExitStatus.merge(status, newStatus))
}

object State {
  private[bloop] val stateCache: StateCache = StateCache.empty
  private[bloop] val compilerCache: CompilerCache = {
    import sbt.internal.inc.bloop.ZincInternals
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"), Logger.get)
  }

  // Improve the caching by using file metadata
  def apply(build: Build, logger: Logger): State = {
    val initialState = State(build, ResultsCache.empty, ExitStatus.Ok, logger)
    val initializedResults = build.projects.foldLeft(initialState.results) {
      case (results, project) => results.initializeResult(project)
    }

    val stateToCache = initialState.copy(results = initializedResults)
    stateCache.updateBuild(state = stateToCache)
    stateToCache
  }
}
