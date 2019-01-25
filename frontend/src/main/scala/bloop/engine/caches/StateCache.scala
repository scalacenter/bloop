package bloop.engine.caches

import bloop.engine.{Build, BuildLoader, State}
import bloop.io.AbsolutePath
import java.util.concurrent.ConcurrentHashMap

import bloop.cli.ExitStatus
import monix.eval.Task

/** Cache that holds the state associated to each loaded build. */
final class StateCache(cache: ConcurrentHashMap[AbsolutePath, State]) {

  /**
   * Gets the state associated to the build loaded from `path`.
   *
   * @param path The path from which the build comes.
   * @return The cached `State` wrapped in an `Option`.
   */
  def getStateFor(path: AbsolutePath): Option[State] = {
    Option(cache.get(path))
  }

  /**
   * Updates the cache with `state`.
   *
   * @param state The state to put in the cache.
   * @return The updated `State`.
   */
  def updateBuild(state: State): State = {
    // Make sure that we always store states with OK statuses in the cache
    cache.put(state.build.origin, state.copy(status = ExitStatus.Ok))
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
  def addIfMissing(from: AbsolutePath, computeBuild: AbsolutePath => Task[State]): Task[State] = {
    Option(cache.get(from)) match {
      case Some(state) =>
        import state.logger
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
                cache.put(from, newState)
                newState
            }
        }
      case None => computeBuild(from).map(s => { cache.put(from, s); s })
    }
  }
}

object StateCache {

  /** A cache that contains no states. */
  def empty: StateCache = new StateCache(new ConcurrentHashMap())
}
