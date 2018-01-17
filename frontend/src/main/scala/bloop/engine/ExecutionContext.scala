package bloop.engine

import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}
import monix.execution.Scheduler

object ExecutionContext {
  private[bloop] val nCPUs = Runtime.getRuntime.availableProcessors()

  // This inlines the implementation of `Executors.newFixedThreadPool` to avoid losing the type
  private[bloop] val executor: ThreadPoolExecutor =
    new ThreadPoolExecutor(nCPUs, nCPUs, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue());

  import monix.execution.Scheduler
  implicit lazy val bspScheduler: Scheduler = Scheduler {
    // TODO: Revisit this.
    java.util.concurrent.Executors.newFixedThreadPool(4)
  }

  implicit val scheduler: Scheduler = Scheduler(executor)
}
