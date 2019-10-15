package bloop.util

import java.util.concurrent.{CancellationException, CompletableFuture, CompletionException}
import java.util.function.BiFunction

import monix.execution.cancelables.SingleAssignmentCancelable
import monix.execution.misc.NonFatal
import monix.execution.schedulers.TrampolinedRunnable
import monix.execution.{Cancelable, CancelableFuture}

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/** Utilities for integration with Java 8 classes
 *
 * Provides methods to convert between `scala.concurrent.Future`
 * and `java.util.concurrent.CompletableFuture`.
 *
 * These utilities have been copy-pasted from the following PR (only
 * merged in the 3.x series): https://github.com/monix/monix/pull/584
 */
object Java8Compat {

  /** Given a registration function that can execute an asynchronous
   * process, executes it and builds a [[CancelableFuture]] value
   * out of it.
   *
   * The given `registration` function can return a [[Cancelable]]
   * reference that can be used to cancel the executed async process.
   * This reference can be [[Cancelable.empty empty]].
   *
   * {{{
   *   def delayedResult[A](f: => A)(implicit s: Scheduler): CancelableFuture[A] =
   *     CancelableFuture.async { complete =>
   *       val task = s.scheduleOnce(10.seconds) { complete(Try(f)) }
   *
   *       Cancelable { () =>
   *         println("Cancelling!")
   *         task.cancel()
   *       }
   *     }
   * }}}
   *
   * This is much like working with Scala's
   * [[scala.concurrent.Promise Promise]], only safer.
   */
  def async[A](
      register: (Try[A] => Unit) => Cancelable
  )(implicit ec: ExecutionContext): CancelableFuture[A] = {

    val p = Promise[A]()
    val cRef = SingleAssignmentCancelable()

    // Light async boundary to guard against stack overflows
    ec.execute(new TrampolinedRunnable {
      def run(): Unit = {
        try {
          cRef := register(p.complete)
        } catch {
          case e if NonFatal(e) =>
            if (!p.tryComplete(Failure(e)))
              ec.reportFailure(e)
        }
      }
    })

    CancelableFuture(p.future, cRef)
  }

  implicit class JavaCompletableFutureUtils[A](val source: CompletableFuture[A]) extends AnyVal {

    /** Convert `CompletableFuture` to [[monix.execution.CancelableFuture]]
     *
     * If the source is cancelled, returned `Future` will never terminate
     */
    def asScala(implicit ec: ExecutionContext): CancelableFuture[A] =
      async(cb => {
        source.handle[Unit](new BiFunction[A, Throwable, Unit] {
          override def apply(result: A, err: Throwable): Unit = {
            err match {
              case null =>
                cb(Success(result))
              case _: CancellationException =>
                ()
              case ex: CompletionException if ex.getCause ne null =>
                cb(Failure(ex.getCause))
              case ex =>
                cb(Failure(ex))
            }
          }
        })
        Cancelable(() => { source.cancel(true); () })
      })
  }

  implicit class ScalaFutureUtils[A](val source: Future[A]) extends AnyVal {

    /** Convert Scala `Future` to Java `CompletableFuture`
     *
     * NOTE: Cancelling resulting future will not have any
     * effect on source
     */
    def asJava(implicit ec: ExecutionContext): CompletableFuture[A] = {
      val cf = new CompletableFuture[A]()
      source.onComplete {
        case Success(a) =>
          cf.complete(a)
        case Failure(ex) =>
          cf.completeExceptionally(ex)
      }
      cf
    }
  }
}
