package bloop.engine.tasks

import bloop.engine.State

import monix.eval.Task
import scala.concurrent.Future

final class BackgroundTask[T](future: Future[T], wrapup: (State, T) => State) {
  def toStateFunction: Task[State => State] = {
    Task.fromFuture(future).map(value => (state: State) => wrapup(state, value))
  }
}

object BackgroundTask {
  def apply[T](future: Future[T]): BackgroundTask[T] = {
    new BackgroundTask(future, (state: State, t: T) => state)
  }
}
