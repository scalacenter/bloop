package bloop.engine

import java.util.concurrent.Executors
import scala.concurrent.{ExecutionContext => ScalaExecutionContext}

class FixedThreadPool(nThreads: Int) extends ExecutionContext {
  def this() = this(Runtime.getRuntime.availableProcessors())
  private val executor = Executors.newFixedThreadPool(nThreads)

  override implicit val context: ScalaExecutionContext =
    ScalaExecutionContext.fromExecutorService(executor)

  override def shutdown(): Unit =
    executor.shutdown()

}
