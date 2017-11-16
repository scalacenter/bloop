package bloop
package tasks

import scala.concurrent.ExecutionContext.Implicits.global

import scala.collection.mutable.Buffer

import utest._

object TaskTest extends TestSuite {

  private implicit val mergeableUnit: Mergeable[Unit] =
    _ => ()

  private implicit def mergeableSet[T]: Mergeable[Set[T]] =
    sets => sets.foldLeft(Set.empty[T])(_ ++ _)

  val tests = Tests {
    "Simple dependency chain" - {
      val buf                     = Buffer.empty[Int]
      def add(x: Int): Task[Unit] = new Task(_ => buf += x, () => ())

      val addOne   = add(1)
      val addTwo   = add(2)
      val addThree = add(3)
      addTwo.dependsOn(addOne)
      addThree.dependsOn(addTwo)

      addThree.run().map { _ =>
        assert(buf == Buffer(1, 2, 3))
      }
    }

    "Two roots, one dependent" - {
      val buf                     = Buffer.empty[Int]
      def add(x: Int): Task[Unit] = new Task(_ => buf += x, () => ())

      val addOne   = add(1)
      val addTwo   = add(2)
      val addThree = add(3)
      addThree.dependsOn(addOne)
      addThree.dependsOn(addTwo)

      addThree.run().map { _ =>
        assert(buf == Buffer(1, 2, 3) || buf == Buffer(2, 1, 3))
      }
    }

    "Un-necessary tasks are not run" - {
      val buf                     = Buffer.empty[Int]
      def add(x: Int): Task[Unit] = new Task(_ => buf += x, () => ())

      val addOne   = add(1)
      val addTwo   = add(2)
      val addThree = add(3)
      val addFour  = add(4)

      addTwo.dependsOn(addOne)
      addThree.dependsOn(addTwo)
      addFour.dependsOn(addThree)

      addThree.run().map { case _ => assert(buf == Buffer(1, 2, 3)) }
    }

    "Results are combined" - {
      def add(x: Int): Task[Set[Int]] = new Task(_ + x, () => ())

      val addOne   = add(1)
      val addTwo   = add(2)
      val addThree = add(3)
      val addFour  = add(4)
      val addFive  = add(5)

      addTwo.dependsOn(addOne)
      addThree.dependsOn(addTwo)
      addFive.dependsOn(addThree)
      addFive.dependsOn(addFour)

      addFive.run.map {
        case Task.Success(numbers) =>
          assert(numbers == Set(1, 2, 3, 4, 5))
        case _ =>
          assert(false)
      }
    }

    "Task failure reports partial results" - {
      implicit val mergeable: Mergeable[Set[Int]] =
        sets => sets.foldLeft(Set.empty[Int])(_ ++ _)

      def add(x: Int): Task[Set[Int]] =
        new Task(nbs => {
          if (x == 3) throw new Exception("Boom!")
          else nbs + x
        }, () => ())

      val addOne   = add(1)
      val addTwo   = add(2)
      val addThree = add(3)
      val addFour  = add(4)
      val addFive  = add(5)

      addThree.dependsOn(addTwo)
      addFour.dependsOn(addOne)
      addFour.dependsOn(addThree)
      addFive.dependsOn(addFour)

      addFive.run().map {
        case Task.Failure(partial, reasons) =>
          assert(partial == Set(1, 2))
          assert(reasons.map(_.getMessage) == List("Boom!"))
      }
    }
  }

  TestRunner.runAsync(tests).map { results =>
    val leafResults = results.leaves.toSeq
    leafResults.foreach(result => assert(result.value.isSuccess))
  }

}
