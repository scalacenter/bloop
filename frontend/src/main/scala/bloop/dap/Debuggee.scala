package bloop.dap

import java.net.InetSocketAddress

import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}

import scala.concurrent.Promise

final class Debuggee(task: DebugSession => Task[_])(implicit scheduler: Scheduler) {
  private val addressPromise = Promise[InetSocketAddress]()
  private var scheduled: CancelableFuture[_] = _

  def start(session: DebugSession): Task[InetSocketAddress] = {
    scheduled = task(session).runAsync
    Task.fromFuture(addressPromise.future)
  }

  def bind(address: InetSocketAddress): Unit = {
    this.addressPromise.success(address)
  }

  def cancel(): Unit = {
    scheduled.cancel()
  }
}
