package bloop.engine

import java.util.concurrent.Executors

object ExecutionContext {
  private val nCPUs = Runtime.getRuntime.availableProcessors()
  private val executor = Executors.newFixedThreadPool(nCPUs)

  implicit val threadPool = scala.concurrent.ExecutionContext.fromExecutorService(executor)
}
