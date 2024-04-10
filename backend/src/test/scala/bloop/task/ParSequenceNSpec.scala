package bloop.task

import scala.concurrent.duration._

import org.junit.Assert
import org.junit.Test
import monix.execution.schedulers.TestScheduler
import java.util.concurrent.atomic.AtomicLong
import scala.util.Success
import scala.util.Failure
import scala.concurrent.Await
import scala.concurrent.Promise

/**
 * Test cases are from https://github.com/monix/monix/blob/2faa2cf7425ab0b88ea57b1ea193bce16613f42a/monix-eval/shared/src/test/scala/monix/eval/TaskParSequenceNSuite.scala
 */
class ParSequenceNSpec {

  private def assertEquals[A](actual: A, expected: A): Unit = {
    Assert.assertEquals(expected, actual)
  }

  @Test
  def empty: Unit = {
    implicit val sh: TestScheduler = TestScheduler()
    val future = Task.parSequenceN(1)(Vector.empty).runAsync
    // check that it completes
    sh.tickOne()
    assertEquals(future.value, Some(Success(Vector.empty)))
  }

  @Test
  def Task_parSequenceN_should_execute_in_parallel_bounded_by_parallelism: Unit = {
    implicit val s: TestScheduler = TestScheduler()

    val num = new AtomicLong(0)
    val task = Task(num.incrementAndGet()).flatMap(_ => Task.sleep(2.seconds))
    val seq = List.fill(100)(task)

    Task.parSequenceN(5)(seq).runAsync

    s.tick()
    assertEquals(num.get(), 5)
    s.tick(2.seconds)
    assertEquals(num.get(), 10)
    s.tick(4.seconds)
    assertEquals(num.get(), 20)
    s.tick(34.seconds)
    assertEquals(num.get(), 100)
  }

  @Test
  def Task_parSequenceN_should_return_result_in_order: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val task = 1.until(10).toList.map(Task.eval(_))
    val res = Task.parSequenceN(2)(task).runAsync

    s.tick()
    assertEquals(res.value, Some(Success(List(1, 2, 3, 4, 5, 6, 7, 8, 9))))
  }

  @Test
  def Task_parSequenceN_should_return_empty_list: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val res = Task.parSequenceN(2)(List.empty).runAsync

    s.tick()
    assertEquals(res.value, Some(Success(List.empty)))
  }

  @Test
  def Task_parSequenceN_should_handle_single_elem: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val task = 1.until(5).toList.map(Task.eval(_))
    val res = Task.parSequenceN(10)(task).runAsync

    s.tick()
    assertEquals(res.value, Some(Success(List(1, 2, 3, 4))))
  }

  @Test
  def Task_parSequenceN_should_on_error_when_one_elem_fail: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val ex = new Exception("dummy")
    val seq = Seq(
      Task(3).delayExecution(3.seconds),
      Task(2).delayExecution(1.second),
      Task(throw ex).delayExecution(1.seconds),
      Task(3).delayExecution(5.seconds)
    )

    val f = Task.parSequenceN(2)(seq).runAsync

    assertEquals(f.value, None)
    s.tick(1.seconds)
    assertEquals(f.value, None)
    s.tick(2.seconds)
    assertEquals(f.value, Some(Failure(ex)))
  }

  @Test
  def Task_parSequenceN_should_be_stack_safe: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val count: Int = 200000
    val tasks = for (_ <- 0 until count) yield Task.now(1)
    val composite = Task.parSequenceN(count)(tasks).map(_.sum)
    val result = composite.runAsync
    s.tick()
    assertEquals(result.value, Some(Success(count)))
  }

  @Test
  def Task_parSequenceN_runAsync_multiple_times: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val state = new AtomicLong(0)
    val task1 = Task { state.incrementAndGet(); 3 }.memoize
    val task2 = task1.map { x =>
      state.incrementAndGet(); x + 1
    }
    val task3 = Task.parSequenceN(2)(List(task2, task2, task2))

    val result1 = task3.runAsync
    s.tick()
    assertEquals(result1.value, Some(Success(List(4, 4, 4))))
    assertEquals(state.get(), 1 + 3)

    val result2 = task3.runAsync
    s.tick()
    assertEquals(result2.value, Some(Success(List(4, 4, 4))))
    assertEquals(state.get(), 1 + 3 + 3)
  }

  /**
   * Cancellation semantic is the major difference between Monix 2 and 3.
   * In Monix 2 cancellation is a mere signal that can be ignored, in Monix 3 it's a hard stop.
   * Here we test whether `Task.parSequenceN` behaves according to the Monix 2 semantics.
   */
  @Test
  def Task_parSequenceN_should_NOT_be_canceled: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val num = new AtomicLong(0)
    val canceled = Promise[Boolean]()
    val seq = Seq(
      Task.unit
        .delayExecution(4.seconds)
        .doOnCancel(Task.eval(canceled.success(true)).void),
      Task(num.compareAndSet(0, 10)).delayExecution(1.second)
    )
    val f = Task.parSequenceN(1)(seq).runAsync

    s.tick(1.second)
    f.cancel()
    s.tick(2.second)

    // doOnCancel uses global scheduler, so we need to wait for it with await rather than tick
    Await.ready(canceled.future, 5.second)

    s.tick(1.day)
    assertEquals(num.get(), 10)
  }

  @Test
  def Task_parSequenceN_workers_dont_wait_for_each_other: Unit = {
    implicit val s: TestScheduler = TestScheduler()
    val seq = Seq(
      Task.sleep(4.seconds).map(_ => 1),
      Task.sleep(1.second).map(_ => 2),
      Task.sleep(2.second).map(_ => 3)
    )
    val f = Task.parSequenceN(2)(seq).runAsync

    s.tick(2.seconds)
    assertEquals(f.value, None)

    s.tick(2.seconds)
    Await.ready(f, 1.second)
    assertEquals(f.value, Some(Success(Vector(1, 2, 3))))
  }

}
