package bloop.task

import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Promise

/**
 * Implementation is based on https://github.com/monix/monix/blob/2faa2cf7425ab0b88ea57b1ea193bce16613f42a/monix-eval/shared/src/main/scala/monix/eval/internal/TaskParSequenceN.scala
 */
private[task] object ParSequenceN {
  def parSequenceN[A](n: Int)(in: Iterable[Task[A]]): Task[Vector[A]] = {
    if (in.isEmpty) {
      Task.now(Vector.empty)
    } else {
      // val isCancelled = new AtomicBoolean(false)
      Task.defer {
        val queue = new java.util.concurrent.ConcurrentLinkedQueue[(Promise[A], Task[A])]()
        val pairs = in.map(t => (Promise[A](), t))
        pairs.foreach(queue.add)
        val errorPromise = Promise[Throwable]()
        val workDone = new AtomicBoolean(false)

        val singleJob: Task[Unit] = Task
          .defer {
            queue.poll() match {
              case null =>
                Task(workDone.set(true))
              case (p, t) =>
                t.transform(
                  value => p.trySuccess(value),
                  error => errorPromise.tryFailure(error)
                )
            }
          }
          .map(_ => ())

        lazy val thunkOfWork: Task[Unit] = Task.defer {
          if (workDone.get()) Task.unit
          else {
            singleJob.flatMap(_ => thunkOfWork)
          }
        }

        val workers = Task.parSequence {
          List.fill(n)(thunkOfWork)
        }

        Task.chooseFirstOf(Task.fromFuture(errorPromise.future), workers).flatMap {
          case Left((err, fb)) =>
            fb.cancel()
            Task.raiseError(err)
          case Right((fa, _)) =>
            fa.cancel()
            val values = pairs.unzip._1.toVector.map(p => Task.fromFuture(p.future))
            Task.sequence(values)
        }
      }
    }

  }
}
