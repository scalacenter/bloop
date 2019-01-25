package bloop.engine.caches

import java.util.concurrent.ConcurrentHashMap

import bloop.logging.Logger
import bloop.cli.CommonOptions
import bloop.engine.{Build, BuildLoader, State, ClientPool}
import bloop.io.AbsolutePath
import bloop.cli.ExitStatus

import monix.eval.Task

/** Cache that holds the state associated to each loaded build. */
final class StateCache(cache: ConcurrentHashMap[AbsolutePath, StateCache.CachedState]) {

  /**
   * Gets the state associated to the build loaded from `path`.
   *
   * @param path The path from which the build comes.
   * @return The cached `State` wrapped in an `Option`.
   */
  def getStateFor(
      path: AbsolutePath,
      pool: ClientPool,
      commonOptions: CommonOptions,
      logger: Logger
  ): Option[State] = {
    Option(cache.get(path)).map { cachedState =>
      State(
        cachedState.build,
        cachedState.results,
        cachedState.compilerCache,
        pool,
        commonOptions,
        ExitStatus.Ok,
        logger
      )
    }
  }

  /**
   * Updates the cache with `state`.
   *
   * @param state The state to put in the cache.
   * @return The updated `State`.
   */
  def updateBuild(state: State): State = {
    cache.put(state.build.origin, StateCache.CachedState.fromState(state))
    state
  }

  /**
   * Returns the state associated to `from`, or the result of `computeBuild` otherwise.
   * If the state was absent, the cache is updated.
   *
   * @param from         Where the build is loaded from.
   * @param computeBuild A function that computes the state from a location.
   * @return The state associated with `from`, or the newly computed state.
   */
  def addIfMissing(
      from: AbsolutePath,
      pool: ClientPool,
      commonOptions: CommonOptions,
      logger: Logger,
      computeBuild: AbsolutePath => Task[State]
  ): Task[State] = {
    getStateFor(from, pool, commonOptions, logger) match {
      case Some(state) =>
        state.build.checkForChange(logger).flatMap {
          case Build.ReturnPreviousState => Task.now(state)
          case Build.UpdateState(createdOrModified, deleted) =>
            BuildLoader.loadBuildFromConfigurationFiles(from, createdOrModified, logger).map {
              newProjects =>
                val currentProjects = state.build.projects
                val toRemove = deleted.toSet ++ newProjects.map(_.origin.path)
                val untouched =
                  currentProjects.collect { case p if !toRemove.contains(p.origin.path) => p }
                val newBuild = state.build.copy(projects = untouched ++ newProjects)
                val newState = state.copy(build = newBuild)
                cache.put(from, StateCache.CachedState.fromState(newState))
                newState
            }
        }
      case None =>
        computeBuild(from).map { state =>
          cache.put(from, StateCache.CachedState.fromState(state))
          state
        }
    }
  }
}

object StateCache {

  /**
   * Represents the most minimal version of [[State]] that can be cached. This
   * class does not leak to any part of the bloop API because it's an implementation
   * detail how the state cache operates and which fields are globally stored or not.
   */
  private[StateCache] case class CachedState(
      build: Build,
      results: ResultsCache,
      compilerCache: bloop.CompilerCache
  )

  private[StateCache] object CachedState {
    def fromState(state: State): CachedState = {
      StateCache.CachedState(state.build, state.results, state.compilerCache)
    }
  }

  /** A cache that contains no states. */
  def empty: StateCache = new StateCache(new ConcurrentHashMap())
}
