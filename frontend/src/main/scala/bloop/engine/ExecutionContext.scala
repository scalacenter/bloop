package bloop.engine

import scala.concurrent.{ExecutionContext => ScalaExecutionContext}

trait ExecutionContext {
  implicit def context: ScalaExecutionContext
  def shutdown(): Unit
}
object ExecutionContext {
  implicit val global = new ExecutionContext {
    override implicit def context: ScalaExecutionContext = ScalaExecutionContext.global
    override def shutdown(): Unit = ()
  }

  def using[T](context: ExecutionContext)(op: ExecutionContext => T): T =
    try op(context)
    finally context.shutdown()

  def withFixedThreadPool[T](op: ExecutionContext => T): T = {
    using(new FixedThreadPool)(op)
  }

  def withGlobal[T](op: ExecutionContext => T): T =
    using[T](global)(op)
}
