/*
package bloop.util.monix

import monix.eval.Task
import monix.execution.Callback
import monix.reactive.Consumer
import monix.execution.Scheduler
import monix.reactive.observers.Subscriber
import monix.execution.cancelables.AssignableCancelable

final class BloopAsyncConsumer[A](f: A => Task[Unit]) extends Consumer[A, Unit] {
  def createSubscriber(cb: Callback[Unit], s: Scheduler): (Subscriber[A], AssignableCancelable) = {
    val out = new Subscriber[A] {
      implicit val scheduler = s
      private[this] var isDone = false

      import scala.concurrent.Future
      import monix.execution.Ack
      import scala.util.control.NonFatal
      def onNext(elem: A): Future[Ack] = {
        try {
          f(elem).coeval.value match {
            case Left(future) =>
              future.map(_ => Ack.Continue)
            case Right(()) =>
              Ack.Continue
          }
        } catch {
          case NonFatal(ex) =>
            onError(ex)
            Ack.Stop
        }
      }

      def onComplete(): Unit =
        if (!isDone) {
          isDone = true
          cb.onSuccess(())
        }

      def onError(ex: Throwable): Unit =
        if (!isDone) {
          isDone = true
          cb.onError(ex)
        }
    }

    (out, AssignableCancelable.dummy)
  }
}

 */
