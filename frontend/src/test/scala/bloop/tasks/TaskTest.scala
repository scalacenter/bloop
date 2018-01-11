package bloop.tasks

import org.junit.Test
import org.junit.Assert.{assertEquals, assertTrue, fail}

import bloop.engine.ExecutionContext.threadPool
import bloop.engine.tasks.{Mergeable, Task}

import scala.collection.mutable.Buffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class TaskTest {

  private implicit val mergeableUnit: Mergeable[Unit] =
    _ => ()

  private implicit def mergeableSet[T]: Mergeable[Set[T]] =
    sets => sets.foldLeft(Set.empty[T])(_ ++ _)

  private def run[T](task: Task[T]): Task.Result[T] =
    Await.result(task.run(), 5.seconds)

  @Test
  def simpleDependencyChain = {
    val buf = Buffer.empty[Int]
    def add(x: Int): Task[Unit] = new Task(_ => buf += x, () => ())

    val addOne = add(1)
    val addTwo = add(2)
    val addThree = add(3)
    addTwo.dependsOn(addOne)
    addThree.dependsOn(addTwo)

    val _ = run(addThree)
    assertEquals(Buffer(1, 2, 3), buf)
  }

  @Test
  def twoRootsOneDependent = {
    val buf = Buffer.empty[Int]
    def add(x: Int): Task[Unit] = new Task(_ => buf.synchronized { buf += x; () }, () => ())

    val addOne = add(1)
    val addTwo = add(2)
    val addThree = add(3)
    addThree.dependsOn(addOne)
    addThree.dependsOn(addTwo)

    val _ = run(addThree)
    assertTrue(s"buf = $buf", buf == Buffer(1, 2, 3) || buf == Buffer(2, 1, 3))
  }

  @Test
  def unnecessaryTasksAreNotRun = {
    val buf = Buffer.empty[Int]
    def add(x: Int): Task[Unit] = new Task(_ => buf.synchronized { buf += x; () }, () => ())

    val addOne = add(1)
    val addTwo = add(2)
    val addThree = add(3)
    val addFour = add(4)

    addTwo.dependsOn(addOne)
    addThree.dependsOn(addTwo)
    addFour.dependsOn(addThree)

    val _ = run(addThree)
    assertEquals(Buffer(1, 2, 3), buf)
  }

  @Test
  def resultsAreCombined = {
    def add(x: Int): Task[Set[Int]] = new Task(_ + x, () => ())

    val addOne = add(1)
    val addTwo = add(2)
    val addThree = add(3)
    val addFour = add(4)
    val addFive = add(5)

    addTwo.dependsOn(addOne)
    addThree.dependsOn(addTwo)
    addFive.dependsOn(addThree)
    addFive.dependsOn(addFour)

    run(addFive) match {
      case Task.Success(numbers) =>
        assertEquals(Set(1, 2, 3, 4, 5), numbers)
      case Task.Failure(_, reasons) =>
        fail("Task execution failed: " + reasons.map(_.getMessage))
    }
  }

  @Test
  def taskFailureReportsPartialResults = {
    implicit val mergeable: Mergeable[Set[Int]] =
      sets => sets.foldLeft(Set.empty[Int])(_ ++ _)

    def add(x: Int): Task[Set[Int]] =
      new Task(nbs => {
        if (x == 3) throw new Exception("Boom!")
        else nbs + x
      }, () => ())

    val addOne = add(1)
    val addTwo = add(2)
    val addThree = add(3)
    val addFour = add(4)
    val addFive = add(5)

    addThree.dependsOn(addTwo)
    addFour.dependsOn(addOne)
    addFour.dependsOn(addThree)
    addFive.dependsOn(addFour)

    run(addFive) match {
      case Task.Failure(partial, reasons) =>
        assertEquals(Set(1, 2), partial)
        assertEquals(List("Boom!"), reasons.map(_.getMessage))
      case Task.Success(_) => fail("The task failure did not report partial results!")
    }
  }

}
