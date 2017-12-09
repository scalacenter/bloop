package bloop.engine

import bloop.CompilerCache
import bloop.cli.ExitStatus
import bloop.engine.caches.{ResultsCache, StateCache}
import bloop.io.Paths
import bloop.logging.Logger

final case class State private (
    build: Build,
    results: ResultsCache,
    compilerCache: CompilerCache,
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
  private var singleCompilerCache: CompilerCache = null
  private def getCompilerCache(logger: Logger): CompilerCache = synchronized {
    if (singleCompilerCache != null) singleCompilerCache
    else {
      import sbt.internal.inc.bloop.ZincInternals
      val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
      val jars = Paths.getCacheDirectory("scala-jars")
      singleCompilerCache = new CompilerCache(provider, jars, logger)
      singleCompilerCache
    }
  }

  private[bloop] def forTests(build: Build, compilerCache: CompilerCache, logger: Logger): State = {
    val initializedResults = build.projects.foldLeft(ResultsCache.getEmpty(logger)) {
      case (results, project) => results.initializeResult(project)
    }
    State(build, initializedResults, compilerCache, ExitStatus.Ok, logger)
  }

  // Improve the caching by using file metadata
  def apply(build: Build, logger: Logger): State = {
    val initializedResults = build.projects.foldLeft(ResultsCache.getEmpty(logger)) {
      case (results, project) => results.initializeResult(project)
    }

    val compilerCache = getCompilerCache(logger)
    val stateToCache = State(build, initializedResults, compilerCache, ExitStatus.Ok, logger)
    stateCache.updateBuild(state = stateToCache)
    stateToCache
  }
}
