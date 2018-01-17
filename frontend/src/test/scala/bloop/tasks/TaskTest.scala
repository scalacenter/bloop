package bloop.engine.tasks

import org.junit.Test
import org.junit.Assert.{assertEquals, assertFalse, assertTrue, fail}
import org.junit.experimental.categories.Category

import bloop.engine.ExecutionContext.scheduler

import scala.collection.mutable.Buffer
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration, DurationInt}

@Category(Array(classOf[bloop.FastTests]))
class TaskTest {

  def await[T](future: Future[T]): T = {
    Await.result(future, 5.seconds)
  }

  @Test
  def simpleDependencyChain = {
    val buf = Buffer.empty[Int]
    def add(x: Int): Task[Unit] = Task(buf += x)

    val addOne = add(1)
    val addTwo = add(2).dependsOn(addOne)
    val addThree = add(3).dependsOn(addTwo)

    val future = addThree.runAsync.map { _ =>
      assertEquals(Buffer(1, 2, 3), buf)
    }
    await(future)
  }

  @Test
  def twoRootsOneDependent = {
    val buf = Buffer.empty[Int]
    def add(x: Int): Task[Unit] = Task(buf.synchronized { buf += x; () })

    val addOne = add(1)
    val addTwo = add(2)
    val addThree = add(3).dependsOn(addOne, addTwo)

    val future = addThree.runAsync.map { _ =>
      assertTrue(s"buf = $buf", buf == Buffer(1, 2, 3) || buf == Buffer(2, 1, 3))
    }
    await(future)
  }

  @Test
  def unnecessaryTasksAreNotRun = {
    val buf = Buffer.empty[Int]
    def add(x: Int): Task[Unit] = Task(buf.synchronized { buf += x; () })

    val addOne = add(1)
    val addTwo = add(2).dependsOn(addOne)
    val addThree = add(3).dependsOn(addTwo)
    val addFour = add(4).dependsOn(addThree)

    val future = addThree.runAsync.map { _ =>
      assertEquals(Buffer(1, 2, 3), buf)
    }
    await(future)
  }

  @Test
  def tasksCanBeMapped = {
    val task = Task(1).map(x => x + 1)
    val future = task.runAsync.map { x =>
      assertEquals(Success(2), x)
    }
    await(future)
  }

  @Test
  def canMapTaskWithDependencies = {
    val buf = Buffer.empty[Int]
    val t1 = Task(buf.synchronized { buf += 1 })
    val t2 = Task(buf.synchronized { buf += 2 })
    val t3 = Task(buf.synchronized { buf += 3 })
    val task = Task(buf.toList.sorted).dependsOn(t1, t2, t3)
    val mappedTask = task.map(nbs => nbs.map(_ + 1))

    val future = mappedTask.runAsync.map { x =>
      assertEquals(Success(List(2, 3, 4)), x)
    }
    await(future)
  }

  @Test
  def canAddDependenciesToTaskWithMap = {
    val buf = Buffer.empty[Int]
    val mappedTask = Task(buf.toList.sorted).map(nbs => nbs.map(_ + 1))

    val t1 = Task(buf.synchronized { buf += 1 })
    val t2 = Task(buf.synchronized { buf += 2 })
    val t3 = Task(buf.synchronized { buf += 3 })

    val task = mappedTask.dependsOn(t1, t2, t3)

    val future = task.runAsync.map { x =>
      assertEquals(Success(List(2, 3, 4)), x)
    }
    await(future)
  }

  @Test
  def tasksCanBeFlatMapped = {
    val task = Task(1).flatMap(x => Task(x + 1))
    val future = task.runAsync.map { x =>
      assertEquals(Success(2), x)
    }
    await(future)
  }

  @Test
  def failuresAreReported = {
    val message = "Boom!"
    val task = Task(throw new Exception(message))
    val future = task.runAsync.map {
      case Failure(ex) => assertEquals(message, ex.getMessage)
      case _ => fail("Expected failure of the task.")
    }
    await(future)
  }

  @Test
  def canAddDependenciesMultipleTimes = {
    val buf = Buffer.empty[Int]
    val t1 = Task(buf.synchronized { buf += 1 })
    val t2 = Task(buf.synchronized { buf += 2 })
    val t3 = Task(buf.synchronized { buf += 3 })
    val task = Task { buf.toList.sorted }.dependsOn(t1).dependsOn(t2).dependsOn(t3)

    val future = task.runAsync.map { x =>
      assertEquals(Success(List(1, 2, 3)), x)
    }
    await(future)
  }

  @Test
  def dependenciesAreRunInParallel = {
    val buf = Buffer.empty[(Long, String)]
    def mkTask(name: String): Task[Unit] = {
      Task {
        buf.synchronized { buf += ((System.nanoTime, "Start")) }
        Thread.sleep(1000)
        buf.synchronized { buf += ((System.nanoTime, "End")) }
        ()
      }
    }
    val t0 = mkTask("t0")
    val t1 = mkTask("t1")
    val t2 = mkTask("t2")
    val task = Task(buf.toList.sortBy(_._1).map(_._2)).dependsOn(t0, t1, t2)

    val future = task.runAsync.map {
      case Success(messages) =>
        assertEquals(6, messages.length.toLong)
        assertEquals(List("Start", "Start", "Start", "End", "End", "End"), messages)
      case _ => fail("Task failed but success was expected.")
    }
    await(future)

  }

  @Test
  def makeTaskGraph = {
    val buf = Buffer.empty[Int]
    val deps = Map(1 -> List(2, 3), 2 -> Nil, 3 -> Nil)
    val mkTask = (node: Int) => Task { buf.synchronized { buf += node } }
    val graph = Task.makeTaskGraph(deps, mkTask)(1)
    val task = Task(buf.toList.sorted).dependsOn(graph)
    val future = task.runAsync.map { x =>
      assertEquals(Success(List(1, 2, 3)), x)
    }
    await(future)
  }

  @Test
  def makeTaskGraphLattice = {
    val buf = Buffer.empty[Int]
    val deps = Map(0 -> Nil, 1 -> List(0), 2 -> List(0), 3 -> List(1, 2))
    val mkTask = (node: Int) => Task { buf.synchronized { buf += node } }
    val graph = Task.makeTaskGraph(deps, mkTask)(3)
    val task = Task(buf.toList.sorted).dependsOn(graph)
    val future = task.runAsync.map { x =>
      assertEquals(Success(List(0, 1, 2, 3)), x)
    }
    await(future)
  }

}
