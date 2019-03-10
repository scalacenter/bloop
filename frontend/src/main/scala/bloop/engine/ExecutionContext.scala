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
  private[bloop] val DefaultTravisCores = 2
  private[bloop] val nCPUs = {
    import java.lang.{Boolean => JBoolean}
    val default = Runtime.getRuntime.availableProcessors()
    def parseIntOrDefault(value: String): Int = {
      try Integer.parseInt(value)
      catch { case scala.util.control.NonFatal(_) => default }
    }

    Option(System.getProperty("bloop.computation.cores")) match {
      case Some(value) => parseIntOrDefault(value)
      case None =>
        Option(System.getenv("BLOOP_COMPUTATION_CORES")) match {
          case Some(value) => parseIntOrDefault(value)
          case None =>
            Option(System.getenv("TRAVIS")) match {
              case Some(value) =>
                try if (JBoolean.parseBoolean(value)) DefaultTravisCores else default
                catch { case scala.util.control.NonFatal(_) => default }
              case None => default
            }
        }
    }
  }

  import monix.execution.Scheduler
  implicit lazy val bspScheduler: Scheduler = Scheduler {
    java.util.concurrent.Executors.newFixedThreadPool(4)
  }

  implicit lazy val scheduler: Scheduler = {
    Scheduler.forkJoin(
      nCPUs,
      nCPUs,
      name = "bloop-computation",
      executionModel = ExecutionModel.Default
    )
  }

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
    ExecutorScheduler(ioExecutor, ioReporter, ExecutionModel.AlwaysAsyncExecution)

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
