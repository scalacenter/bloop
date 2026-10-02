package bloop.rifle

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ExecutorService, Executors, ScheduledExecutorService, ThreadFactory}

final case class BloopThreads(
    jsonrpc: ExecutorService,
    startServerChecks: ScheduledExecutorService
) {
  def shutdown(): Unit = {
    jsonrpc.shutdown()
    startServerChecks.shutdown()
  }
}

object BloopThreads {

  /**
   * `startServerChecks` runs both the periodic server start-up checks and blocking Nailgun calls
   * (such as `bloop about`, see `bloop.rifle.internal.Operations.about`). It has several threads
   * so that a hung call can't block the start-up check or a retry queued behind it, and is kept
   * small so that an overloaded server isn't hammered with concurrent requests.
   */
  def create(): BloopThreads = {
    val jsonrpc = Executors.newFixedThreadPool(4, daemonThreadFactory("scala-cli-bsp-jsonrpc"))
    val startServerChecks = Executors.newScheduledThreadPool(
      4,
      daemonThreadFactory("scala-cli-bloop-rifle")
    )
    BloopThreads(jsonrpc, startServerChecks)
  }

  private def daemonThreadFactory(prefix: String): ThreadFactory =
    new ThreadFactory {
      val counter = new AtomicInteger
      def threadNumber() = counter.incrementAndGet()
      def newThread(r: Runnable) =
        new Thread(r, s"$prefix-thread-${threadNumber()}") {
          setDaemon(true)
          setPriority(Thread.NORM_PRIORITY)
        }
    }
}
