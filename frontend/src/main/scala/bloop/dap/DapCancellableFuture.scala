package bloop.dap

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Success

import ch.epfl.scala.debugadapter.CancelableFuture

import bloop.task.Task

import monix.execution.Cancelable
import monix.execution.Scheduler

private class DapCancellableFuture(future: Future[Unit], cancelable: Cancelable)
    extends ch.epfl.scala.debugadapter.CancelableFuture[Unit] {
  override def future(): Future[Unit] = future
  override def cancel(): Unit = cancelable.cancel()
}

object DapCancellableFuture {
  def runAsync(task: Task[Unit])(implicit ioScheduler: Scheduler): CancelableFuture[Unit] = {
    val promise = Promise[Unit]()
    val cancelable = task
      .doOnFinish {
        case None => Task(promise.success(()))
        case Some(t) => Task(promise.failure(t))
      }
      .doOnCancel(
        Task {
          promise.tryComplete(Success(()))
          ()
        }
      )
      .runAsync(ioScheduler)
    new DapCancellableFuture(promise.future, cancelable)
  }
}
