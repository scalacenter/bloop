package bloop.engine.caches

import bloop.engine.State
import bloop.io.{AbsolutePath, FileTracker}
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
    def computeAndSave: Task[State] = computeBuild(from).map(s => { cache.put(from, s); s })

    Option(cache.get(from)) match {
      case Some(state) =>
        state.build.changed(state.logger) match {
          case FileTracker.Unchanged(None) => Task.now(state)
          case FileTracker.Unchanged(Some(t)) =>
            Task.now(state.copy(build = state.build.copy(tracker = t)))
          case FileTracker.Changed => computeAndSave
        }
      case None => computeAndSave
    }
  }

  /** All the states contained in this cache. */
  def allStates: Iterator[State] = {
    import scala.collection.JavaConverters._
    cache.asScala.valuesIterator
  }
}

object StateCache {

  /** A cache that contains no states. */
  def empty: StateCache = new StateCache(new ConcurrentHashMap())
}
