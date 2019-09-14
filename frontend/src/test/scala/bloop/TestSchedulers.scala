package bloop
import java.util.concurrent.{Executors, ThreadFactory}

import monix.execution.{ExecutionModel, Scheduler}
import monix.execution.atomic.Atomic

object TestSchedulers {
  // We limit the threads count so that we don't have any thread leak
  def async(name: String, threads: Int): Scheduler = {
    val i = Atomic(0)
    val factory: ThreadFactory = new Thread(_, s"$name-${i.getAndIncrement()}")

    val pool = Executors.newFixedThreadPool(threads, factory)
    Scheduler(pool, ExecutionModel.AlwaysAsyncExecution)
  }
}
