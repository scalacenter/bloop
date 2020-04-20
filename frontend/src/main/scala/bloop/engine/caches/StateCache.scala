package bloop.engine.caches

import java.util.concurrent.ConcurrentHashMap

import bloop.data.ClientInfo
import bloop.data.WorkspaceSettings
import bloop.logging.Logger
import bloop.cli.CommonOptions
import bloop.engine.{Build, BuildLoader, State, ClientPool}
import bloop.io.AbsolutePath
import bloop.cli.ExitStatus
import bloop.util.Environment

import monix.eval.Task

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

  private[bloop] def getRawCachedBuildFor(path: AbsolutePath): Option[Build] =
    Option(cache.get(path)).map(_.build)

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
  def loadState(
      from: AbsolutePath,
      client: ClientInfo,
      pool: ClientPool,
      commonOptions: CommonOptions,
      logger: Logger,
      createState: Build => State,
      clientSettings: Option[WorkspaceSettings]
  ): Task[State] = {
    val empty = Build(from, Nil)
    if (from == Environment.defaultBloopDirectory) return Task.now(createState(empty))
    val previousState = getStateFor(from, client, pool, commonOptions, logger)
    val build = previousState.map(_.build).getOrElse(empty)

    build.checkForChange(clientSettings, logger).flatMap {
      case Build.ReturnPreviousState => Task.now(previousState.getOrElse(createState(empty)))
      case Build.UpdateState(created, modified, deleted, changed, settings, writeSettings) =>
        if (writeSettings) {
          settings.foreach { settings =>
            // Write settings, swallow any error and report it to the user instead of propagating it
            try WorkspaceSettings.writeToFile(from, settings, logger)
            catch {
              case e: Throwable =>
                logger.displayWarningToUser(s"Failed to write workspace settings: ${e.getMessage}")
                logger.trace(e)
            }
          }
        }

        val createdOrModified = created ++ modified
        BuildLoader
          .loadBuildIncrementally(from, createdOrModified, changed, settings, logger)
          .map { newProjects =>
            val newState = previousState match {
              case Some(state) =>
                // Update the build incrementally and then create a new updated state
                val currentProjects = state.build.loadedProjects
                val toRemove = deleted.toSet ++ newProjects.map(_.project.origin.path)
                val untouched = currentProjects.collect {
                  case p if !toRemove.contains(p.project.origin.path) => p
                }

                val newBuild = state.build.copy(loadedProjects = untouched ++ newProjects)
                state.copy(build = newBuild)
              // Create a new state since there was no previous one
              case None => createState(Build(from, newProjects))
            }
            cache.put(from, StateCache.CachedState.fromState(newState))
            newState
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
