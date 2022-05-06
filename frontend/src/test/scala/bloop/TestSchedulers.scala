package bloop
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory

import monix.execution.ExecutionModel
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import java.util.concurrent.atomic.AtomicInteger

object TestSchedulers {

  def threadFactory(name: String): ThreadFactory = {
    val i = new AtomicInteger
    r =>
      val t = new Thread(r, s"$name-${i.incrementAndGet()}")
      t.setDaemon(true)
      t
  }

  // We limit the threads count so that we don't have any thread leak
  def async(name: String, threads: Int): Scheduler = {
    val factory = threadFactory(name)
    val pool = Executors.newFixedThreadPool(threads, factory)
    Scheduler(pool, ExecutionModel.AlwaysAsyncExecution)
  }
}
