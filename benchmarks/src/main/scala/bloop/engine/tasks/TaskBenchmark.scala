package bloop.engine.tasks

import org.openjdk.jmh.annotations.Benchmark

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import bloop.engine.ExecutionContext.threadPool

object TaskBenchmark {
  implicit val unitMergeable: Mergeable[Unit] = _ => ()
  def makeDependencyChain(length: Int): Task[Unit] = {
    var i = 1
    var task: Task[Unit] = new Task(_ => (), () => ())
    while (i < length) {
      val newTask: Task[Unit] = new Task(_ => (), () => ())
      newTask.dependsOn(task)
      task = newTask
      i += 1
    }
    task
  }

  def makeDependencyTree(span: Int, height: Int): Task[Unit] = {
    val root: Task[Unit] = new Task(_ => (), () => ())
    if (height == 0) root
    else {
      var i = 0
      while (i < span) {
        val subTreeRoot = makeDependencyTree(span, height - 1)
        root.dependsOn(subTreeRoot)
        i += 1
      }
      root
    }
  }

}
class TaskBenchmark {

  @Benchmark
  def tenElementsDependencyChain(): Unit = {
    val task = TaskBenchmark.makeDependencyChain(length = 10)
    val _ = Await.result(task.run(), Duration.Inf)
  }

  @Benchmark
  def hundredElementsDependencyChain(): Unit = {
    val task = TaskBenchmark.makeDependencyChain(length = 100)
    val _ = Await.result(task.run(), Duration.Inf)
  }

  @Benchmark
  def height5BinaryDependencyTree(): Unit = {
    val task = TaskBenchmark.makeDependencyTree(span = 2, height = 5)
    val _ = Await.result(task.run(), Duration.Inf)
  }

  @Benchmark
  def height5Span5DependencyTree(): Unit = {
    val task = TaskBenchmark.makeDependencyTree(span = 5, height = 5)
    val _ = Await.result(task.run(), Duration.Inf)
  }

}
