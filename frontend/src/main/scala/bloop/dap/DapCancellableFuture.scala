package bloop.dap

import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}

import scala.concurrent.{Future, Promise}

private class DapCancellableFuture(future: Future[Unit], cancelable: Cancelable)
    extends ch.epfl.scala.debugadapter.CancelableFuture[Unit] {
  override def future(): Future[Unit] = future
  override def cancel(): Unit = cancelable.cancel()
}

object DapCancellableFuture {
  def runAsync(
      task: Task[Unit],
      ioScheduler: Scheduler
  ): ch.epfl.scala.debugadapter.CancelableFuture[Unit] = {
    val promise = Promise[Unit]()
    val cancelable = task
      .doOnFinish {
        case None => Task(promise.success(()))
        case Some(t) => Task(promise.failure(t))
      }
      .doOnCancel(Task(promise.success(())))
      .runAsync(ioScheduler)
    new DapCancellableFuture(promise.future, cancelable)
  }
}
