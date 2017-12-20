package bloop.engine

import java.util.concurrent.{LinkedBlockingQueue, ThreadPoolExecutor, TimeUnit}

object ExecutionContext {
  private[bloop] val nCPUs = Runtime.getRuntime.availableProcessors()

  // This inlines the implementation of `Executors.newFixedThreadPool` to avoid losing the type
  private[bloop] val executor: ThreadPoolExecutor =
    new ThreadPoolExecutor(nCPUs, nCPUs, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue());

  // If you change the backing of this thread pool, please make sure you modify users of `executor`
  implicit val threadPool: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.fromExecutorService(executor)
}
