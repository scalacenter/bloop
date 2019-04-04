package bloop.util.monix

import java.util.concurrent.TimeUnit
import monix.execution.Ack.{Continue, Stop}
import monix.execution.cancelables.MultiAssignCancelable
import monix.execution.cancelables.CompositeCancelable
import monix.execution.{Ack, Cancelable}
import monix.reactive.Observable
import monix.reactive.observers.Subscriber
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.duration.{Duration, FiniteDuration}

final class BloopBufferTimedObservable[+A](
    source: Observable[A],
    timespan: FiniteDuration
) extends Observable[Seq[A]] {

  require(timespan > Duration.Zero, "timespan must be strictly positive")
  def unsafeSubscribeFn(out: Subscriber[Seq[A]]): Cancelable = {
    val periodicTask = MultiAssignCancelable()

    val connection = source.unsafeSubscribeFn(new Subscriber[A] with Runnable { self =>
      implicit val scheduler = out.scheduler

      private[this] val timespanMillis = timespan.toMillis
      // MUST BE synchronized by `self`
      private[this] var ack: Future[Ack] = Continue
      // MUST BE synchronized by `self`
      private[this] var buffer = ListBuffer.empty[A]
      // MUST BE synchronized by `self`
      private[this] var expiresAt = scheduler.clockMonotonic(TimeUnit.MILLISECONDS) + timespanMillis

      locally {
        // Scheduling the first tick, in the constructor
        periodicTask := out.scheduler.scheduleOnce(timespanMillis, TimeUnit.MILLISECONDS, self)
      }

      // Runs periodically, every `timespan`
      def run(): Unit = self.synchronized {
        val now = scheduler.clockMonotonic(TimeUnit.MILLISECONDS)
        // Do we still have time remaining?
        if (now < expiresAt) {
          // If we still have time remaining, it's either a scheduler
          // problem, or we rushed to signaling the bundle upon reaching
          // the maximum size in onNext. So we sleep some more.
          val remaining = expiresAt - now
          periodicTask := scheduler.scheduleOnce(remaining, TimeUnit.MILLISECONDS, self)
        } else if (buffer != null) {
          // The timespan has passed since the last signal so we need
          // to send the current bundle
          sendNextAndReset(now).syncOnContinue {
            // Schedule the next tick, but only after we are done
            // sending the bundle
            periodicTask := scheduler.scheduleOnce(0, TimeUnit.MILLISECONDS, self)
          }
        }
        ()
      }

      // Must be synchronized by `self`
      private def sendNextAndReset(now: Long): Future[Ack] = {
        val oldBuffer = buffer.toList
        // Reset
        buffer = ListBuffer.empty[A]
        // Setting the time of the next scheduled tick
        expiresAt = now + timespanMillis
        ack = ack.syncTryFlatten.syncFlatMap {
          case Continue => out.onNext(oldBuffer)
          case Stop => Stop
        }
        ack
      }

      def onNext(elem: A): Future[Ack] = self.synchronized {
        val now = scheduler.clockMonotonic(TimeUnit.MILLISECONDS)
        buffer.append(elem)

        if (expiresAt > now) Continue
        else sendNextAndReset(now)
      }

      def onError(ex: Throwable): Unit = self.synchronized {
        periodicTask.cancel()
        ack = Stop
        buffer = null
        out.onError(ex)
      }

      def onComplete(): Unit = self.synchronized {
        periodicTask.cancel()

        if (buffer.nonEmpty) {
          val bundleToSend = buffer.toList
          // In case the last onNext isn't finished, then
          // we need to apply back-pressure, otherwise this
          // onNext will break the contract.
          ack.syncOnContinue {
            out.onNext(bundleToSend)
            out.onComplete()
          }
        } else {
          // We can just stream directly
          out.onComplete()
        }

        // GC relief
        buffer = null
        // Ensuring that nothing else happens
        ack = Stop
      }
    })

    CompositeCancelable(connection, periodicTask)
  }
}
