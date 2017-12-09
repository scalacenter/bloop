package bloop.engine

import bloop.CompilerCache
import bloop.cli.{CommonOptions, ExitStatus}
import bloop.engine.caches.{ResultsCache, StateCache}
import bloop.io.Paths
import bloop.logging.Logger

/**
 * Represents the state for a given build.
 *
 * An state is aggressively cached by bloop so that operations that target a build can reuse
 * the state that has already been created before. The state is immutable and allows different
 * builds to change common values utilized by all the bloop operations.
 *
 * @param build The build with which the state is associated.
 * @param results The results cache that contains all the analysis file for a build.
 * @param compilerCache The cache that has pointers to hot compilers (shared across other states).
 * @param commonOptions The environment options necessary for input and output.
 * @param status The status in which the state is currently.
 * @param logger The logger that is used and is associated with a given build.
 */
final case class State private (
    build: Build,
    results: ResultsCache,
    compilerCache: CompilerCache,
    commonOptions: CommonOptions,
    status: ExitStatus,
    logger: Logger
) {
  /* TODO: Improve the handling and merging of different status. Use the status to report errors. */
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
    State(build, initializedResults, compilerCache, CommonOptions.default, ExitStatus.Ok, logger)
  }

  // Improve the caching by using file metadata
  def apply(build: Build, logger: Logger): State = {
    val results = build.projects.foldLeft(ResultsCache.getEmpty(logger)) {
      case (results, project) => results.initializeResult(project)
    }

    val options = CommonOptions.default
    val compilerCache = getCompilerCache(logger)
    val stateToCache = State(build, results, compilerCache, options, ExitStatus.Ok, logger)
    stateCache.updateBuild(state = stateToCache)
    stateToCache
  }
}
