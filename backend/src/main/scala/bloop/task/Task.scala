package bloop.task

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

import scala.collection.generic.CanBuildFrom
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import monix.eval.{Task => MonixTask}
import monix.execution.Callback
import monix.execution.Cancelable
import monix.execution.CancelableFuture
import monix.execution.Scheduler

/**
 * This task was introduced as a compatibilty layer to ublock bloop migration from monix2.
 *  there is an important difference between monix2 and monix3  in cancellation mechanic:
 *  - monix2 - cancel is a signal that triggers `doOnCancel` callbacks and the whole task anyway completes.
 *  - monix3 - cancel actually cancel the runLoop of task and turns `CancellableFuture` into incomletable one.
 *
 * Bloop is hugely relies on `doOnCancel` usage and old cancellation mechanic.
 * Actually, most of bsp-methods are kind of function `(Input, State) => State`.
 * runLoop cancellation doesn't fit for bloop at all.
 * So the idea is: perform cancels as in monix2, do an actual execution on monix3.
 * This approach allows to unblock bsp4s/monix/scala upgrades (monix2 isn't supported and isn't published for scala 2.13).
 * In theory we can switch it on some other effect-library and preserve the codebase without significant rewrites.
 *
 * @dos65 notes:
 *   There is an `disableRunLoopCancellation` option in monix3 but even with using there were some other differences in cancellation.
 *   At least, the issue was that after enabling this option it was impossible to cancel compileTask.
 *   Unfortunately, I couldn't find why and where it happens and if it might be fixed in monix3.
 *   Also, at that moment, the ongoing work on monix4 started, so I decided to no go this way.
 */
sealed trait Task[+A] { self =>

  final def map[B](f: A => B): Task[B] = Task.Map(f, self, List.empty)

  final def flatMap[B](f: A => Task[B]): Task[B] = Task.FlatMap(f, self, List.empty)

  private def applyCancel(f: () => Unit): Task[A] = {
    self match {
      case node: Task.FlatMap[_, A] => node.copy(cancels = f :: node.cancels)
      case node: Task.Map[_, A] => node.copy(cancels = f :: node.cancels)
      case node: Task.Wrap[_] => node.copy(cancels = f :: node.cancels)
      case node: Task.Create[_] => node.copy(cancels = f :: node.cancels)
      case node: Task.Suspend[_] => node.copy(cancels = f :: node.cancels)
      case node: Task.Transform[_, A] => node.copy(cancels = f :: node.cancels)
    }
  }

  final def doOnCancel(f: => Task[Unit]): Task[A] =
    applyCancel(() => f.runAsync(monix.execution.Scheduler.Implicits.global))

  final def doOnFinish(f: Option[Throwable] => Task[Unit]): Task[A] =
    self.materialize.flatMap { v =>
      val next = v match {
        case Failure(e) => f(Some(e))
        case Success(_) => f(None)
      }
      next.flatMap(_ => Task.fromTry(v))
    }

  final def materialize: Task[Try[A]] =
    Task.Transform(
      (t: MonixTask[A]) => t.materialize,
      self,
      List.empty
    )

  final def dematerialize[B](implicit ev: A <:< Try[B]): Task[B] =
    Task.Transform(
      (t: MonixTask[A]) => t.dematerialize,
      self,
      List.empty
    )

  final def memoize: Task[A] = {
    import Task.MemoState
    val ref =
      new AtomicReference[MemoState[A]](MemoState.Empty)

    def fromRef(sh: Scheduler): CancelableFuture[A] = {
      ref.get() match {
        case MemoState.Empty =>
          if (ref.compareAndSet(MemoState.Empty, MemoState.Reserved)) {
            val future = self.runAsync(sh)
            ref.set(MemoState.Memoized(future))
            future
          } else {
            fromRef(sh)
          }
        case MemoState.Reserved => fromRef(sh)
        case MemoState.Memoized(f) => f
      }
    }

    Task.create[A] { (sh, cb) =>
      val future = fromRef(sh)
      future.onComplete(v => cb(v))(sh)
      Cancelable(() => future.cancel())
    }
  }

  final def flatten[B](implicit ev: A <:< Task[B]): Task[B] =
    self.flatMap(a => a)

  final def executeOn(s: Scheduler, forceAsync: Boolean = true): Task[A] =
    Task.Transform(
      (t: MonixTask[A]) => t.executeOn(s, forceAsync),
      self,
      List.empty
    )

  final def asyncBoundary: Task[A] =
    Task.Transform(
      (t: MonixTask[A]) => t.asyncBoundary,
      self,
      List.empty
    )

  final def asyncBoundary(s: Scheduler): Task[A] =
    Task.Transform(
      (t: MonixTask[A]) => t.asyncBoundary(s),
      self,
      List.empty
    )

  final def transform[R](fa: A => R, fe: Throwable => R): Task[R] =
    Task.Transform(
      (t: MonixTask[A]) => t.redeem(fe, fa),
      self,
      List.empty
    )

  def onErrorRecover[U >: A](pf: PartialFunction[Throwable, U]): Task[U] =
    self.materialize.flatMap {
      case Failure(e) =>
        pf.lift(e) match {
          case None => Task.raiseError(e)
          case Some(value) => Task(value)
        }
      case Success(value) => Task(value)
    }

  def onErrorFallbackTo[U >: A](f: Task[U]): Task[U] =
    self.materialize.flatMap {
      case Failure(_) => f
      case Success(value) => Task(value)
    }

  def timeout(duration: FiniteDuration): Task[A] = {
    timeoutTo(duration, Task.raiseError(new TimeoutException()))
  }

  def as[B](b: => B): Task[B] =
    self.map(_ => b)

  def timeoutTo[B >: A](duration: FiniteDuration, backup: Task[B]): Task[B] = {
    Task
      .chooseFirstOf(
        self,
        Task.sleep(duration)
      )
      .flatMap {
        case Left((a, _)) =>
          Task.now(a)
        case Right((a, _)) =>
          a.cancel()
          backup
      }
  }

  def failed: Task[Throwable] =
    self.materialize.flatMap {
      case Failure(e) => Task(e)
      case Success(_) => Task.raiseError(new NoSuchElementException("failed"))
    }

  def onErrorRestartIf(f: Throwable => Boolean): Task[A] =
    self.materialize.flatMap {
      case Failure(e) if f(e) => self.onErrorRestartIf(f)
      case Failure(e) => Task.raiseError(e)
      case Success(v) => Task(v)
    }

  def restartUntil(f: A => Boolean): Task[A] = {
    self.flatMap { a =>
      val stop = f(a)
      if (stop) Task.now(a) else self.restartUntil(f)
    }
  }

  def onErrorHandleWith[B >: A](f: Throwable => Task[B]): Task[B] =
    self.materialize.flatMap {
      case Failure(e) => f(e)
      case Success(v) => Task.now(v)
    }

  def executeAsync: Task[A] =
    Task.Transform(
      (t: MonixTask[A]) => t.executeAsync,
      self,
      List.empty
    )

  def delayExecution(timespan: FiniteDuration): Task[A] =
    Task.sleep(timespan).flatMap(_ => self)

  def toMonixTask(implicit sh: Scheduler): MonixTask[A] = {
    MonixTask.fromFuture(self.runAsync)
  }

  def *>[B](f: => Task[B]): Task[B] =
    self.flatMap(_ => f)

  def runAsync(implicit s: Scheduler): CancelableFuture[A] = {
    def reverse(t: Task[Any], acc: List[Task[Any]]): List[Task[Any]] = {
      t match {
        case a: Task.Wrap[_] => a :: acc
        case a: Task.Suspend[_] => a :: acc
        case a: Task.Create[_] => a :: acc
        case a: Task.Map[_, _] => reverse(a.prev, a :: acc)
        case a: Task.FlatMap[_, _] => reverse(a.prev, a :: acc)
        case a: Task.Transform[_, _] => reverse(a.prev, a :: acc)
      }
    }

    def registerCancels(
        t: MonixTask[Any],
        cancels: List[() => Unit],
        callbacks: Task.Callbacks
    ): MonixTask[Any] = {
      cancels match {
        case Nil => t
        case _ =>
          MonixTask {
            callbacks.push(cancels)
          }.flatMap(_ => t)
            .flatMap(v => { callbacks.pop; MonixTask.now(v) })
      }
    }

    def fold(
        t: Task[Any],
        callbacks: Task.Callbacks
    ): MonixTask[Any] = {
      val init: MonixTask[Any] = MonixTask.unit
      reverse(t, List.empty).foldLeft(init) {
        case (exec, node) =>
          val (nextExec, cancels) = node match {
            case n: Task.Wrap[_] =>
              (exec.flatMap(_ => n.underlying), n.cancels)
            case n: Task.Suspend[_] =>
              val t = exec.flatMap { _ =>
                val next = n.thunk.asInstanceOf[() => Task[Any]]()
                fold(next, callbacks)
              }
              (t, n.cancels)
            case n: Task.Map[_, _] =>
              (exec.map(a => n.f.asInstanceOf[Any => Any](a)), n.cancels)
            case n: Task.FlatMap[_, _] =>
              val t = exec.flatMap { a =>
                val next = n.f.asInstanceOf[Any => Task[Any]](a)
                fold(next, callbacks)
              }
              (t, n.cancels)
            case n: Task.Transform[_, _] =>
              (n.f.asInstanceOf[MonixTask[Any] => MonixTask[Any]](exec), n.cancels)
            case n: Task.Create[_] =>
              val t = exec.flatMap { _ =>
                val register =
                  (sh: Scheduler, cb: Callback[Throwable, Any]) => {
                    val cancel = n.register(sh, cb.asInstanceOf[Callback[Throwable, Any]])
                    callbacks.push(List(() => cancel.cancel()))
                    ()
                  }
                MonixTask.async0[Any](register).map(v => { callbacks.pop; v })
              }
              (t, n.cancels)
          }
          registerCancels(nextExec, cancels, callbacks)
      }
    }

    val callbacks = Task.Callbacks()
    val exec = fold(self, callbacks)
    val main = (MonixTask.shift *> exec).runToFuture.asInstanceOf[CancelableFuture[A]]

    CancelableFuture(
      main,
      Cancelable { () =>
        val tasks = callbacks.takeAllAndCancelNext
        val _ = tasks.flatten.foreach(f => f())
      }
    )
  }

}

object Task {

  sealed trait MemoState[+A]
  object MemoState {
    case object Empty extends MemoState[Nothing]
    case object Reserved extends MemoState[Nothing]
    final case class Memoized[A](f: CancelableFuture[A]) extends MemoState[A]
  }

  class Callbacks(ref: AtomicReference[List[List[() => Unit]]]) {
    def push(tasks: List[() => Unit]): Unit = {
      ref.get() match {
        case null =>
          val _ = tasks.foreach(f => f())
        case prev =>
          val next = tasks :: prev
          if (!ref.compareAndSet(prev, next))
            push(tasks)
      }
    }

    def pop: Unit = {
      val curr = ref.get()
      curr match {
        case _ :: tail =>
          if (!ref.compareAndSet(curr, tail))
            pop
        case _ =>
      }
    }

    def takeAllAndCancelNext: List[List[() => Unit]] = {
      val curr = ref.get()
      if (!ref.compareAndSet(curr, null))
        takeAllAndCancelNext
      else
        curr
    }
  }

  object Callbacks {
    def apply(): Callbacks = new Callbacks(new AtomicReference(List.empty))
  }

  final case class Suspend[A](thunk: () => Task[A], cancels: List[() => Unit]) extends Task[A]
  final case class Wrap[A](underlying: MonixTask[A], cancels: List[() => Unit]) extends Task[A]

  final case class Map[A, B](f: A => B, prev: Task[A], cancels: List[() => Unit]) extends Task[B]

  final case class FlatMap[A, B](
      f: A => Task[B],
      prev: Task[A],
      cancels: List[() => Unit]
  ) extends Task[B]

  final case class Transform[A, B](
      f: MonixTask[A] => MonixTask[B],
      prev: Task[A],
      cancels: List[() => Unit]
  ) extends Task[B]

  final case class Create[A](
      register: (Scheduler, Callback[Throwable, A]) => Cancelable,
      cancels: List[() => Unit]
  ) extends Task[A]

  def now[A](a: A): Task[A] = Wrap(MonixTask.now(a), List.empty)

  def apply[A](f: => A): Task[A] =
    Wrap(MonixTask.eval(f), List.empty)

  def eval[A](f: => A): Task[A] = Task(f)

  val unit: Task[Unit] = Wrap(MonixTask.unit, List.empty)

  def create[A](register: (Scheduler, Callback[Throwable, A]) => Cancelable): Task[A] = {
    Create(register, List.empty)
  }

  def defer[A](fa: => Task[A]): Task[A] = Suspend(() => fa, List.empty)

  def gather[A](in: Iterable[Task[A]]): Task[List[A]] =
    parSequence(in)

  def parSequence[A](in: Iterable[Task[A]]): Task[List[A]] = {
    val size = in.size
    if (in.isEmpty) {
      Task.now(List.empty)
    } else {
      Task.create[List[A]] { (sh, cb) =>
        val state = new AtomicReference(Vector.empty[(A, Int)])

        def completeWithError(e: Throwable): Unit = {
          state.get() match {
            case null =>
            case curr =>
              if (state.compareAndSet(curr, null))
                cb.onError(e)
              else
                completeWithError(e)
          }
        }

        def result(a: A, idx: Int): Unit = {
          state.get() match {
            case null =>
            case acc =>
              val next = acc :+ ((a, idx))
              if (next.length == size) {
                val out = next.toList.sortBy(_._2).map(_._1)
                cb.onSuccess(out)
              } else if (!state.compareAndSet(acc, next))
                result(a, idx)
          }
        }

        val all = in.zipWithIndex.map {
          case (task, idx) =>
            val t = task.runAsync(sh)
            t.onComplete {
              case Failure(e) => completeWithError(e)
              case Success(a) => result(a, idx)
            }(sh)
            t
        }
        Cancelable { () =>
          all.foreach(_.cancel())
        }
      }
    }
  }

  def gatherUnordered[A](in: Iterable[Task[A]]): Task[List[A]] =
    parSequenceUnordered(in)

  def parSequenceUnordered[A](in: Iterable[Task[A]]): Task[List[A]] = {
    val size = in.size
    if (in.isEmpty) {
      Task.now(List.empty)
    } else {
      Task.create[List[A]] { (sh, cb) =>
        val state = new AtomicReference(Vector.empty[A])

        def completeWithError(e: Throwable): Unit = {
          state.get() match {
            case null =>
            case curr =>
              if (state.compareAndSet(curr, null))
                cb.onError(e)
              else
                completeWithError(e)
          }
        }

        def result(a: A): Unit = {
          state.get() match {
            case null =>
            case acc =>
              val next = acc :+ a
              if (next.length == size)
                cb.onSuccess(next.toList)
              else if (!state.compareAndSet(acc, next))
                result(a)
          }
        }

        val all = in.map { task =>
          val t = task.runAsync(sh)
          t.onComplete {
            case Failure(e) => completeWithError(e)
            case Success(a) => result(a)
          }(sh)
          t
        }
        Cancelable { () =>
          all.foreach(_.cancel())
        }
      }
    }
  }

  def parSequenceN[A](n: Int)(in: Iterable[Task[A]]): Task[List[A]] = {
    val chunks = in.grouped(n).toList.map(group => Task.parSequence(group))
    Task.sequence(chunks).map(_.flatten)
  }

  def fromFuture[A](f: Future[A]): Task[A] =
    Wrap(MonixTask.fromFuture(f), List.empty)

  def zip2[A, B](a: Task[A], b: Task[B]): Task[(A, B)] =
    mapBoth(a, b) { case (a, b) => (a, b) }

  def mapBoth[A, B, R](a: Task[A], b: Task[B])(f: (A, B) => R): Task[R] = {
    Task
      .create[(A, B)] { (sh, cb) =>
        val result = new AtomicReference[(Option[A], Option[B])]((None, None))
        val fa = a.runAsync(sh)
        val fb = b.runAsync(sh)

        def aResult(a: A): Unit = {
          result.get() match {
            case (None, Some(b)) =>
              result.set(null)
              cb.onSuccess((a, b))
            case curr @ (None, None) =>
              if (!result.compareAndSet(curr, (Some(a), None))) {
                aResult(a)
              }
            case _ =>
          }
        }
        def bResult(b: B): Unit = {
          result.get() match {
            case (Some(a), None) =>
              result.set(null)
              cb.onSuccess((a, b))
            case curr @ (None, None) =>
              if (!result.compareAndSet(curr, (None, Some(b)))) {
                bResult(b)
              }
            case _ =>
          }
        }

        fa.onComplete {
          case Failure(e) =>
            result.set(null)
            cb.onError(e)
          case Success(a) => aResult(a)
        }(sh)

        fb.onComplete {
          case Failure(e) =>
            result.set(null)
            cb.onError(e)
          case Success(b) => bResult(b)
        }(sh)

        Cancelable(() => {
          fa.cancel()
          fb.cancel()
        })
      }
      .map({ case (a, b) => f(a, b) })

  }

  def raiseError[A](ex: Throwable): Task[A] =
    Wrap(MonixTask.raiseError(ex), List.empty)

  def sequence[A, M[X] <: Iterable[X]](in: M[Task[A]])(implicit
      bf: CanBuildFrom[M[Task[A]], A, M[A]]
  ): Task[M[A]] = {
    import scala.collection.mutable.Builder

    def iterate(
        iter: Iterator[Task[A]],
        builder: Builder[A, M[A]]
    ): Task[M[A]] = {
      if (iter.hasNext) {
        val task = iter.next()
        task.flatMap { a =>
          iterate(iter, builder += a)
        }
      } else Task.now(builder.result())
    }

    iterate(in.iterator, bf(in))
  }

  def chooseFirstOf[A, B](
      a: Task[A],
      b: Task[B]
  ): Task[Either[(A, CancelableFuture[B]), (CancelableFuture[A], B)]] = {
    create {
      (
          sh: Scheduler,
          cb: Callback[Throwable, Either[(A, CancelableFuture[B]), (CancelableFuture[A], B)]]
      ) =>
        val latch = new AtomicBoolean(false)
        val fa = a.runAsync(sh)
        val fb = b.runAsync(sh)
        fa.onComplete { v =>
          if (latch.compareAndSet(false, true)) {
            v match {
              case Failure(e) => cb.onError(e)
              case Success(a) => cb.onSuccess(Left((a, fb)))
            }
          }
        }(sh)

        fb.onComplete { v =>
          if (latch.compareAndSet(false, true)) {
            v match {
              case Failure(e) => cb.onError(e)
              case Success(b) => cb.onSuccess(Right((fa, b)))
            }
          }
        }(sh)

        Cancelable(() => {
          fa.cancel()
          fb.cancel()
        })
    }
  }

  def sleep(duration: FiniteDuration): Task[Unit] =
    Wrap(MonixTask.sleep(duration), List.empty)

  def fromTry[A](v: Try[A]): Task[A] =
    v match {
      case Failure(e) => Task.raiseError(e)
      case Success(v) => Task.now(v)
    }

  def deferFutureAction[A](f: Scheduler => Future[A]): Task[A] = {
    Task.create { (sh, cb) =>
      val future = f(sh)
      future.onComplete {
        case Failure(e) => cb.onError(e)
        case Success(v) => cb.onSuccess(v)
      }(sh)
      Cancelable.empty
    }
  }

  def deferAction[A](f: Scheduler => Task[A]): Task[A] =
    Task
      .create[Scheduler] { (sh, cb) =>
        cb.onSuccess(sh)
        Cancelable.empty
      }
      .flatMap(sh => f(sh))

  def liftMonixTask[A](t: MonixTask[A], cancel: () => Unit): Task[A] =
    Task.create { (sh, cb) =>
      t.runToFuture(sh)
        .onComplete {
          case Success(v) => cb.onSuccess(v)
          case Failure(e) => cb.onError(e)
        }(sh)
      Cancelable(cancel)
    }

  def liftMonixTaskUncancellable[A](t: MonixTask[A]): Task[A] = {
    liftMonixTask(t, () => ())
  }

  def raceMany[A](tasks: Task[A]*): Task[A] = {
    Task.create[A] { (sh, cb) =>
      val isCompleted = new AtomicBoolean(false)
      def complete(v: Try[A]): Unit = {
        if (isCompleted.compareAndSet(false, true)) {
          cb(v)
        }
      }
      val cancellables = tasks.map { t =>
        val f = t.runAsync(sh)
        f.onComplete(complete(_))(sh)
        f
      }
      Cancelable { () => cancellables.foreach(_.cancel()) }
    }
  }
}
