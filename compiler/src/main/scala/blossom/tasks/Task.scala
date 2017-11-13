package blossom.tasks

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class Task[T: Mergeable](op: T => T, onComplete: () => Unit) {
  private val semaphore    = new Semaphore(0)
  private val inputs       = mutable.Buffer.empty[T]
  private val dependencies = mutable.Buffer.empty[Task[T]]
  private val dependents   = mutable.Buffer.empty[Task[T]]
  private val started      = new AtomicBoolean(false)

  def dependsOn(task: Task[T]): Unit = {
    dependencies += task
    task.dependents += this
  }

  def run()(implicit ec: ExecutionContext,
            mergeable: Mergeable[T]): Future[T] = {
    preExecution()
    Future {
      semaphore.acquire(dependencies.length)
      val input  = mergeable.merge(inputs)
      val result = op(input)
      postExecution(result)
      result
    }
  }

  private def preExecution()(implicit ec: ExecutionContext): Unit = {
    dependencies.foreach { dep =>
      if (dep.started.compareAndSet(false, true)) dep.run()
    }
  }

  private def postExecution(result: T): Unit = {
    dependents.foreach { dep =>
      dep.inputs += result
      dep.semaphore.release()
    }
    onComplete()
  }
}
