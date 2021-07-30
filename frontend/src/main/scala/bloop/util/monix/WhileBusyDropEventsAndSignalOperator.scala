package bloop.util.monix

import monix.execution.Ack
import monix.execution.Ack.{Continue, Stop}
import scala.util.control.NonFatal
import monix.reactive.Observable.Operator
import monix.reactive.observers.Subscriber
import monix.execution.atomic.AtomicBoolean

import scala.concurrent.Future
import scala.collection.mutable
import scala.util.Success
import scala.util.Failure

final class BloopWhileBusyDropEventsAndSignalOperator[A](onOverflow: Seq[A] => A)
    extends Operator[A, A] {

  def apply(out: Subscriber[A]): Subscriber.Sync[A] =
    new Subscriber.Sync[A] {
      implicit val scheduler = out.scheduler

      private[this] var ack = Continue: Future[Ack]
      private[this] val bufferedEvents = new mutable.ListBuffer[A]()
      private[this] var isDone = false

      def onNext(elem: A) = ack.synchronized {
        if (isDone) Stop
        else {
          ack.syncTryFlatten match {
            case Continue =>
              // Protects calls to user code from within the operator and
              // stream the error downstream if it happens, but if the
              // error happens because of calls to `onNext` or other
              // protocol calls, then the behavior should be undefined.
              var streamError = true
              try {
                streamError = false
                ack = out.onNext(elem)
                if (ack eq Stop) Stop else Continue
              } catch {
                case NonFatal(ex) if streamError =>
                  onError(ex)
                  Stop
              }

            case Stop => Stop
            case async =>
              val eventsSize = bufferedEvents.synchronized {
                bufferedEvents += elem
                bufferedEvents.size
              }

              if (eventsSize == 1) {
                var streamError = true
                ack.syncOnComplete {
                  case Failure(e) => ()
                  case Success(Stop) => ()
                  case Success(Continue) =>
                    try {
                      val overflowedEvents = bufferedEvents.synchronized {
                        val events = bufferedEvents.toList
                        bufferedEvents.clear()
                        events
                      }
                      val message = onOverflow(overflowedEvents)
                      streamError = false
                      ack = out.onNext(message)
                    } catch {
                      case NonFatal(ex) if streamError =>
                        onError(ex)
                        ()
                    }
                }
                Continue
              } else {
                Continue
              }
          }
        }
      }

      def onError(ex: Throwable) =
        if (!isDone) {
          isDone = true
          out.onError(ex)
        }

      def onComplete() =
        if (!isDone) {
          isDone = true
          val overflowedEvents = bufferedEvents.synchronized {
            val events = bufferedEvents.toList
            bufferedEvents.clear()
            events
          }

          if (overflowedEvents.isEmpty)
            out.onComplete()
          else {
            ack.syncOnContinue {
              // Protects calls to user code from within the operator and
              // stream the error downstream if it happens, but if the
              // error happens because of calls to `onNext` or other
              // protocol calls, then the behavior should be undefined.
              var streamError = true
              try {
                val message = onOverflow(overflowedEvents)
                streamError = false
                out.onNext(message)
                out.onComplete()
              } catch {
                case NonFatal(ex) if streamError =>
                  out.onError(ex)
              }
            }
            ()
          }
        }
    }
}
