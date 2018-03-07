package bloop.engine.caches

import bloop.engine.State
import bloop.io.{AbsolutePath, FileTracker}
import java.util.concurrent.ConcurrentHashMap

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
   * Updates the cache with `state`. The
   *
   * @param state The state to put in the cache.
   * @return The passed `State`, with a status code of `Ok`.
   */
  def updateBuild(state: State): State = {
    cache.put(state.build.origin, state)
  }

  /**
   * Returns the state associated to `from`, or the result of `computeBuild` otherwise.
   * If the state was absent, the cache is updated.
   *
   * @param from         Where the build is loaded from.
   * @param computeBuild A function that computes the state from a location.
   * @return The state associated with `from`, or the newly computed state.
   */
  def addIfMissing(from: AbsolutePath, computeBuild: AbsolutePath => State): State = {
    val state = cache.computeIfAbsent(from, p => computeBuild(p))
    state.build.changed(state.logger) match {
      case FileTracker.Unchanged(None) =>
        state
      case FileTracker.Unchanged(Some(csum)) =>
        state.copy(build = state.build.copy(originChecksum = csum))
      case FileTracker.Changed =>
        val updatedState = computeBuild(from)
        val _ = cache.put(from, updatedState)
        updatedState
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
