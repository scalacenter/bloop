package bloop.task

import cats.effect.Concurrent
import cats.effect.concurrent.{Deferred, Ref}
import monix.eval.{Task => MonixTask}
import monix.execution.atomic.AtomicBoolean

class ObservableDeferred[A](
    ref: Deferred[MonixTask, A],
    state: Ref[MonixTask, Boolean],
    syncFlag: AtomicBoolean
) {
  def complete(value: A): MonixTask[Unit] = {
    MonixTask.defer {
      ref
        .complete(value)
        .flatMap { _ =>
          syncFlag.set(true)
          state.set(true)
        }
    }.uncancelable
  }

  def isCompletedUnsafe: Boolean = syncFlag.get

  def isCompleted: MonixTask[Boolean] = state.get

  def get: MonixTask[A] = ref.get
}

object ObservableDeferred {

  def apply[A](implicit F: Concurrent[MonixTask]): MonixTask[ObservableDeferred[A]] =
    for {
      ref <- Deferred[MonixTask, A]
      state <- Ref[MonixTask].of(false)
    } yield new ObservableDeferred(ref, state, AtomicBoolean(false))

}
