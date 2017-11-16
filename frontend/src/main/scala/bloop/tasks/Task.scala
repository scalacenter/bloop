package bloop.tasks

import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

class Task[T](op: T => T, onComplete: () => Unit)(implicit mergeable: Mergeable[T]) {
  private val semaphore    = new Semaphore(0)
  private val inputs       = mutable.Buffer.empty[Task.Result[T]]
  private val dependencies = mutable.Buffer.empty[Task[T]]
  private val dependents   = mutable.Buffer.empty[Task[T]]
  private val started      = new AtomicBoolean(false)

  def dependsOn(task: Task[T]): Unit = {
    dependencies += task
    task.dependents += this
  }

  def run()(implicit ec: ExecutionContext): Future[Task.Result[T]] = {
    preExecution()
    Future {
      semaphore.acquire(dependencies.length)

      val (failures, successes) =
        inputs.foldLeft((Nil, Nil): (List[Task.Failure[T]], List[Task.Success[T]])) {
          case ((fs, ss), fail: Task.Failure[_]) => (fail :: fs, ss)
          case ((fs, ss), succ: Task.Success[_]) => (fs, succ :: ss)
        }

      val input = mergeable.merge(successes.map(_.value))
      val result =
        if (failures.isEmpty) {
          Task.execute(op, input)
        } else {
          Task.Failure(input, failures)
        }
      postExecution(result)
      result
    }
  }

  private def preExecution()(implicit ec: ExecutionContext): Unit = {
    dependencies.foreach { dep =>
      if (dep.started.compareAndSet(false, true)) dep.run()
    }
  }

  private def postExecution(result: Task.Result[T]): Unit = {
    dependents.foreach { dep =>
      dep.inputs += result
      dep.semaphore.release()
    }
    onComplete()
  }
}

object Task {
  sealed trait Result[T]
  case class Success[T](value: T)                                  extends Result[T]
  case class Failure[T](partialResult: T, reasons: Seq[Throwable]) extends Result[T]
  object Failure {
    def apply[T](partialResult: T, failures: Seq[Failure[T]])(implicit mergeable: Mergeable[T]): Failure[T] = {
      val result  = mergeable.merge(Seq(partialResult) ++ failures.map(_.partialResult))
      val reasons = failures.flatMap(_.reasons)
      Failure(result, reasons)
    }
  }

  private def execute[T](op: T => T, input: T): Result[T] =
    try Success(op(input))
    catch {
      case ex: Throwable => Failure(input, ex :: Nil)
    }
}
