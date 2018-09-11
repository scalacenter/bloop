package monix.reactive.internal.consumers.bloop

import monix.eval.{Callback, Task}
import monix.execution.Ack.{Continue, Stop}
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.execution.cancelables.{AssignableCancelable, CompositeCancelable}
import monix.execution.misc.NonFatal
import monix.reactive.{Consumer, Observable}
import monix.reactive.observers.Subscriber

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

// Fork of `FoldLeftAsyncConsumer` from Monix
final class FoldLeftAsyncConsumer[-A, +R](
    initial: () => R,
    f: (R, A) => Task[R]
) extends ((Observable[A]) => Task[R]) with Serializable { self =>

  def createSubscriber(cb: Callback[R], s: Scheduler): (Subscriber[A], AssignableCancelable) = {
    val cancelables = new ListBuffer[Cancelable]()
    val out = new Subscriber[A] {
      implicit val scheduler = s
      private[this] var isDone = false
      private[this] var state = initial()

      def onNext(elem: A): Future[Ack] = {
        // Protects calls to user code from within the operator,
        // as a matter of contract.
        try {
          val task = f(state, elem).transform(update => {
            state = update
            Continue
          }, error => {
            onError(error)
            Stop
          })

          val future = task.runAsync
          cancelables.+=(future)
          future
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

  final def apply(source: Observable[A]): Task[R] = {
    Task.create[R] { (scheduler, cb) =>
      val (out, consumerSubscription) = createSubscriber(cb, scheduler)
      val sourceSubscription = source.subscribe(out)
      CompositeCancelable(sourceSubscription, consumerSubscription)
    }
  }
}

object FoldLeftAsyncConsumer {
  def consume[S, A](initial: => S)(f: (S, A) => Task[S]): FoldLeftAsyncConsumer[A, S] =
    new FoldLeftAsyncConsumer[A, S](initial _, f)
}
