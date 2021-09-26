package bloop.util.monix

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.cancelables.{AssignableCancelable, CompositeCancelable}
import monix.execution.misc.NonFatal
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.observers.Subscriber
import monix.reactive.{Consumer, Observable}

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import monix.execution.CancelableFuture

// Fork of `FoldLeftAsyncConsumer` from Monix, not thread-safe
final class FoldLeftAsyncConsumer[A, R](
    initial: () => R,
    f: (R, A) => Task[R]
) extends Consumer[A, R] {

  def createSubscriber(cb: Callback[R], s: Scheduler): (Subscriber[A], AssignableCancelable) = {
    val cancelables = new ListBuffer[Cancelable]()
    val out = new Subscriber[A] {
      implicit val scheduler = s
      private[this] var isDone = false
      private[this] var state = initial()
      private[this] var running: Future[Ack] = Continue

      def onNext(elem: A): Future[Ack] = running.synchronized {
        def triggerTaskExecution = {
          val task = f(state, elem).transform(
            update => {
              state = update
              Continue: Continue
            },
            error => {
              onError(error)
              Stop: Stop
            }
          )

          val future = task.runAsync
          cancelables.+=(future)
          // Unregister from the cancelables when future completes
          future.transform(
            ack => { cancelables.-=(future); ack },
            t => { cancelables.-=(future); t }
          )
        }

        // Protects calls to user code from within the operator,
        // as a matter of contract.
        try {
          running.syncTryFlatten match {
            case Continue =>
              running = triggerTaskExecution
              running
            case Stop => Stop
            case async =>
              running = async.flatMap { _ =>
                triggerTaskExecution
              }
              running
          }
        } catch {
          case NonFatal(ex) =>
            onError(ex)
            Stop
        }
      }

      def onComplete(): Unit =
        if (!isDone) {
          isDone = true
          cb.onSuccess(state)
        }

      def onError(ex: Throwable): Unit =
        if (!isDone) {
          isDone = true
          cb.onError(ex)
        }
    }

    val completer = Cancelable(() => out.onComplete())
    val cancelable = AssignableCancelable.multi { () =>
      Cancelable.cancelAll(completer :: cancelables.toList)
    }

    (out, cancelable)
  }

  override def apply(source: Observable[A]): Task[R] = {
    Task.create[R] { (scheduler, cb) =>
      val (out, consumerSubscription) = createSubscriber(cb, scheduler)
      val sourceSubscription = source.subscribe(out)
      CompositeCancelable(sourceSubscription, consumerSubscription)
    }
  }

}

object FoldLeftAsyncConsumer {
  def consume[S, A](initial: => S)(f: (S, A) => Task[S]): Consumer[A, S] =
    new FoldLeftAsyncConsumer[A, S](initial _, f)
}
