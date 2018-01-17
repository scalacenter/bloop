package bloop.engine.tasks

import org.openjdk.jmh.annotations.Benchmark

import bloop.engine.ExecutionContext.scheduler

object TaskBenchmark {
  def makeDependencyChain(length: Int): Task[Unit] = {
    var i = 1
    var task: Task[Unit] = Task(())
    while (i < length) {
      val newTask: Task[Unit] = Task(())
      newTask.dependsOn(task)
      task = newTask
      i += 1
    }
    task
  }

  def makeDependencyTree(span: Int, height: Int): Task[Unit] = {
    val root: Task[Unit] = Task(())
    if (height == 0) root
    else {
      var i = 0
      val subTrees = new Array[Task[Unit]](span)
      while (i < span) {
        val subTreeRoot = makeDependencyTree(span, height - 1)
        subTrees(i) = subTreeRoot
        i += 1
      }
      root.dependsOn(subTrees.toSeq: _*)
      root
    }
  }

}
class TaskBenchmark {

  @Benchmark
  def tenElementsDependencyChain(): Unit = {
    val task = TaskBenchmark.makeDependencyChain(length = 10)
    val _ = task.await()
  }

  @Benchmark
  def hundredElementsDependencyChain(): Unit = {
    val task = TaskBenchmark.makeDependencyChain(length = 100)
    val _ = task.await()
  }

  @Benchmark
  def height5BinaryDependencyTree(): Unit = {
    val task = TaskBenchmark.makeDependencyTree(span = 2, height = 5)
    val _ = task.await()
  }

  @Benchmark
  def height5Span5DependencyTree(): Unit = {
    val task = TaskBenchmark.makeDependencyTree(span = 5, height = 5)
    val _ = task.await()
  }

}
