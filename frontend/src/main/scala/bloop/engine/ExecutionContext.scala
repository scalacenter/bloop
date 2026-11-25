package bloop.engine

import java.lang.Thread.UncaughtExceptionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import monix.execution.ExecutionModel
import monix.execution.Scheduler
import monix.execution.UncaughtExceptionReporter
import monix.execution.schedulers.ExecutorScheduler

object ExecutionContext {
  private[bloop] val DefaultTravisCores = 2
  // Initialize some System properties before we initialize the execution context
  private val nCPUs: Int = {
    import java.lang.{Boolean => JBoolean}
    val default = Runtime.getRuntime.availableProcessors()
    def parseIntOrDefault(value: String): Int = {
      try Integer.parseInt(value)
      catch { case scala.util.control.NonFatal(_) => default }
    }

    val cpus = Option(System.getProperty("bloop.computation.cores")) match {
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

    if (cpus != default) {
      System.setProperty("scala.concurrent.context.numThreads", cpus.toString)
    }
    cpus
  }

  @volatile private var outOfMemoryHandler: OutOfMemoryError => Unit =
    (_: OutOfMemoryError) => ()

  private[bloop] def setOutOfMemoryHandler(handler: OutOfMemoryError => Unit): Unit =
    outOfMemoryHandler = handler

  // This is necessary because -XX:+ExitOnOutOfMemoryError misses Java-level OutOfMemoryErrors
  // https://bugs.openjdk.org/browse/JDK-8257790
  private[bloop] def installFatalOutOfMemoryHandler(): Unit = {
    setOutOfMemoryHandler { oom =>
      try System.err.println(s"Fatal OutOfMemoryError, terminating Bloop: ${oom.getMessage}")
      catch { case _: Throwable => () }
      Runtime.getRuntime.halt(3)
    }
    Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler {
      override def uncaughtException(t: Thread, e: Throwable): Unit =
        ioReporter.reportFailure(e)
    })
  }

  private[bloop] def findOutOfMemoryError(t: Throwable): Option[OutOfMemoryError] = {
    var current: Throwable = t
    var depth = 0
    while (current != null && depth < 16) {
      current match {
        case oom: OutOfMemoryError => return Some(oom)
        case _ =>
          val next = current.getCause
          if (next eq current)
            current = null
          else
            current = next
      }
      depth += 1
    }
    None
  }

  private[bloop] def handleIfOutOfMemory(t: Throwable): Unit =
    findOutOfMemoryError(t).foreach(outOfMemoryHandler)

  implicit lazy val scheduler: Scheduler = {
    Scheduler.computation(
      parallelism = nCPUs,
      name = "bloop-computation",
      daemonic = true,
      reporter = ioReporter,
      executionModel = ExecutionModel.Default
    )
  }

  val ioReporter: UncaughtExceptionReporter =
    new UncaughtExceptionReporter {
      override def reportFailure(ex: Throwable): Unit = {
        try UncaughtExceptionReporter.default.reportFailure(ex)
        catch { case _: Throwable => () }
        handleIfOutOfMemory(ex)
      }
    }

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
  ): ThreadFactory = { (r: Runnable) =>
    {
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
