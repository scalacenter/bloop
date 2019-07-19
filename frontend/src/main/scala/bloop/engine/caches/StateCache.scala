package bloop.engine.caches

import java.util.concurrent.ConcurrentHashMap

import bloop.data.ClientInfo
import bloop.data.WorkspaceSettings
import bloop.logging.Logger
import bloop.cli.CommonOptions
import bloop.engine.{Build, BuildLoader, State, ClientPool}
import bloop.io.AbsolutePath
import bloop.cli.ExitStatus

import monix.eval.Task
import bloop.data.LoadedBuild

/** Cache that holds the state associated to each loaded build. */
final class StateCache(cache: ConcurrentHashMap[AbsolutePath, StateCache.CachedState]) {

  /**
   * Gets a cached state for the following parameters.
   *
   * @param path The config path where the build is defined.
   * @param client The client-specific information of the call-site.
   * @param pool The session-specific pool.
   * @param commonOptions The session-specific common options.
   * @param logger The logger instance.
   * @return The cached `State`, if any.
   */
  def getStateFor(
      path: AbsolutePath,
      client: ClientInfo,
      pool: ClientPool,
      commonOptions: CommonOptions,
      logger: Logger
  ): Option[State] = {
    Option(cache.get(path)).map { cachedState =>
      State(
        cachedState.build,
        cachedState.results,
        cachedState.compilerCache,
        client,
        pool,
        commonOptions,
        ExitStatus.Ok,
        logger
      )
    }
  }

  /**
   * Gets an updated state (if any) associated to the build of the given state,
   * while keeping all the session-specific information in it.
   *
   * @param state The state that we want to find a newer replacement for.
   * @return The newest state associated with the build of `state`.
   */
  def getUpdatedStateFrom(
      state: State
  ): Option[State] = {
    getStateFor(state.build.origin, state.client, state.pool, state.commonOptions, state.logger)
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
      client: ClientInfo,
      pool: ClientPool,
      commonOptions: CommonOptions,
      logger: Logger,
      computeBuild: AbsolutePath => Task[State],
      incomingSettings: Option[WorkspaceSettings],
      reapplySettings: Boolean
  ): Task[State] = {
    getStateFor(from, client, pool, commonOptions, logger) match {
      case Some(state) =>
        state.build.checkForChange(logger, incomingSettings, reapplySettings).flatMap {
          case Build.ReturnPreviousState => Task.now(state)
          case Build.UpdateState(createdOrModified, deleted, settingsChanged) =>
            BuildLoader
              .loadBuildFromConfigurationFiles(from, createdOrModified, settingsChanged, logger)
              .map {
                case LoadedBuild(newProjects, settings) =>
                  val currentProjects = state.build.projects
                  val toRemove = deleted.toSet ++ newProjects.map(_.origin.path)
                  val untouched =
                    currentProjects.collect { case p if !toRemove.contains(p.origin.path) => p }
                  val newBuild =
                    state.build.copy(projects = untouched ++ newProjects, settings = settings)
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
