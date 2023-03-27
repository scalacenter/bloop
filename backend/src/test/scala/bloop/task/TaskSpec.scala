package bloop.task

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.Await
import scala.concurrent.duration._

import monix.execution.Cancelable
import monix.execution.Scheduler.Implicits.global
import org.junit.Assert._
import org.junit.Test

class TaskSpec {

  @Test
  def base: Unit = {
    val t = Task.now(42).map(_.toString()).flatMap(a => Task.now(s"$a $a"))
    val v = Await.result(t.runAsync, Duration.Inf)
    assertEquals(v, "42 42")
  }

  @Test
  def cancel1: Unit = {
    val ref = new AtomicBoolean(false)

    def waitTrue(): Task[Unit] =
      if (ref.get()) Task.unit
      else
        Task {
          Thread.sleep(100)
        }.flatMap(_ => waitTrue())

    val future = waitTrue().doOnCancel(Task(ref.set(true))).runAsync

    (Task.sleep(200.milli) *> Task(future.cancel())).runAsync

    // check that it completes
    Await.result(future, 1.second)
  }

  @Test
  def cancel2: Unit = {
    val ref = new AtomicBoolean(false)

    def waitTrue(): Task[Unit] = {
      if (ref.get()) Task.unit
      else
        Task {
          Thread.sleep(100)
        }.flatMap(_ => waitTrue())
    }

    val future = Task {
      Thread.sleep(100)
    }.flatMap(_ => waitTrue().doOnCancel(Task { ref.set(true) })).runAsync

    future.cancel()

    // check that it completes
    Await.result(future, 2.second)
  }

  @Test
  def doOnFinish: Unit = {

    def assertInvokesCallback[A](t: Task[A]): Unit = {
      val ref = new AtomicBoolean(false)
      val future = t.doOnFinish(_ => Task(ref.set(true))).runAsync
      Await.result(future.transform(s => scala.util.Success(s)), 1.second)
      assert(ref.get)
    }

    assertInvokesCallback(Task(Thread.sleep(100)))
    assertInvokesCallback(Task("asdsad".toInt)) // <- error
  }

  @Test
  def sequence: Unit = {
    val future = Task
      .sequence(
        List(1, 2, 3, 4).map(i => Task.now(i))
      )
      .runAsync
    val out = Await.result(future, 1.second)
    assertEquals(out, List(1, 2, 3, 4))
  }

  @Test
  def parSequenceUnordered: Unit = {
    val future =
      Task
        .parSequenceUnordered(
          // List(Task.sleep(5), Task.sleep(4), ... , Task.sleep(0))
          (0 to 5).reverse.map(i => Task.sleep(i.second).as(i))
        )
        .runAsync
    val out = Await.result(future, 7.second)
    // the faster task completes the earlier it appears in result coll
    // expected is List(0, 1, ..., 5)
    assertEquals(out, (0 to 5).toList)
  }

  @Test
  def parSequence: Unit = {
    val future =
      Task
        .parSequence(
          (0 to 5).reverse.map(i => Task.sleep(i.second).as(i))
        )
        .runAsync
    val out = Await.result(future, 7.second)
    assertEquals(out, (0 to 5).reverse)
  }

  @Test
  def create: Unit = {
    val future =
      Task
        .create[Int] { (sh, cb) =>
          sh.scheduleOnce(
            100,
            TimeUnit.MILLISECONDS,
            new Runnable {
              def run(): Unit = cb.onSuccess(42)
            }
          )
          Cancelable.empty
        }
        .runAsync
    val out = Await.result(future, 1.second).toLong
    assertEquals(out, 42)
  }

  @Test
  def mapBoth: Unit = {
    val future = Task
      .mapBoth(
        Task(1),
        Task(2)
      )(_ + _)
      .runAsync

    val out = Await.result(future, 1.second).toLong
    assertEquals(out, 3)
  }

  @Test
  def memoize1: Unit = {
    val memoizedTime = Task
      .create[Long] { (_, cb) =>
        cb.onSuccess(System.currentTimeMillis())
        Cancelable.empty
      }
      .memoize

    val t = for {
      one <- memoizedTime
      two <- memoizedTime
    } yield (one, two)

    val (one, two) = Await.result(t.runAsync, 1.second)
    assertEquals(one, two)
  }

  @Test
  def memoize2: Unit = {
    val memoizedTime = Task
      .create[Long] { (_, cb) =>
        cb.onSuccess(System.currentTimeMillis())
        Cancelable.empty
      }
      .memoize

    // evalation is cached across different materialization of task(runAsync)
    val future = for {
      one <- memoizedTime.runAsync
      two <- memoizedTime.runAsync
    } yield (one, two)

    val (one, two) = Await.result(future, 1.second)
    assertEquals(one, two)

  }

  @Test
  def timeoutTo2: Unit = {
    val ref = new AtomicBoolean(true)
    val t1 = Task.unit.delayExecution(500.milli)
    val t2 = Task(ref.set(false))
    val withTimeout = t1.timeoutTo(1.second, t2)

    Await.result((withTimeout *> Task.sleep(2.seconds)).runAsync, 3.second)
    assertEquals(true, ref.get())
  }

}
