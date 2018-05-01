package bloop.engine

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.{
  LinkedBlockingQueue,
  SynchronousQueue,
  ThreadFactory,
  ThreadPoolExecutor,
  TimeUnit
}

import monix.execution.{ExecutionModel, UncaughtExceptionReporter}
import monix.execution.schedulers.ExecutorScheduler

object ExecutionContext {
  private[bloop] val nCPUs = Runtime.getRuntime.availableProcessors() + 1

  // This inlines the implementation of `Executors.newFixedThreadPool` to avoid losing the type
  private[bloop] val executor: ThreadPoolExecutor =
    new ThreadPoolExecutor(nCPUs, nCPUs, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue());

  import monix.execution.Scheduler
  implicit lazy val bspScheduler: Scheduler = Scheduler {
    // TODO: Revisit this.
    java.util.concurrent.Executors.newFixedThreadPool(4)
  }

  implicit lazy val scheduler: Scheduler = Scheduler(executor)

  val ioReporter = UncaughtExceptionReporter.LogExceptionsToStandardErr
  lazy val ioExecutor: ThreadPoolExecutor = {
    val threadFactory = monixThreadFactoryBuilder("bloop-io", ioReporter, daemonic = true)
    new ThreadPoolExecutor(
      0,
      Int.MaxValue,
      60,
      TimeUnit.SECONDS,
      new SynchronousQueue[Runnable](false),
      threadFactory
    )
  }

  implicit lazy val ioScheduler: Scheduler =
    ExecutorScheduler(ioExecutor, ioReporter, ExecutionModel.Default)

  // Inlined from `monix.execution.schedulers.ThreadFactoryBuilder`
  private def monixThreadFactoryBuilder(
      name: String,
      reporter: UncaughtExceptionReporter,
      daemonic: Boolean
  ): ThreadFactory = {
    new ThreadFactory {
      def newThread(r: Runnable) = {
        val thread = new Thread(r)
        thread.setName(name + "-" + thread.getId)
        thread.setDaemon(daemonic)
        thread.setUncaughtExceptionHandler(new UncaughtExceptionHandler {
          override def uncaughtException(t: Thread, e: Throwable): Unit =
            reporter.reportFailure(e)
        })

        thread
      }
    }
  }
}
