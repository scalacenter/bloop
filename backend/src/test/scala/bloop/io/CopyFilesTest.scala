package bloop.io

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import monix.execution.{ExecutionModel, Scheduler}
import org.junit.Test

import scala.concurrent.Await
import scala.concurrent.duration.{Duration, FiniteDuration}

class CopyFilesTest {
  @Test
  def copyFilesTest(): Unit = {
    val scheduler = Scheduler.io()
    val origin = AbsolutePath(System.getProperty("user.home"))
      .resolve("Code")
      .resolve("bloop")
      .resolve("backend")
      .resolve("target")
    def target = AbsolutePath(Files.createTempDirectory("copy-parallel-benchmark"))

    timed(Some("sequential")) {
      Paths.copyDirectoriesSequentially(origin, target)
    }
    Paths.delete(target)

    timed(Some("sequential")) {
      Paths.copyDirectoriesSequentially(origin, target)
    }
    Paths.delete(target)

    timed(Some("sequential")) {
      Paths.copyDirectoriesSequentially(origin, target)
    }
    Paths.delete(target)

    timed(Some("sequential")) {
      Paths.copyDirectoriesSequentially(origin, target)
    }
    Paths.delete(target)

    timed(Some("sequential")) {
      Paths.copyDirectoriesSequentially(origin, target)
    }
    Paths.delete(target)

    timed(Some("sequential")) {
      Paths.copyDirectoriesSequentially(origin, target)
    }
    Paths.delete(target)

    timed(Some("5 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 5)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("5 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 5)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("5 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 5)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("5 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 5)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("5 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 5)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("5 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 5)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("10 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 10)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("10 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 10)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("10 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 10)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("10 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 10)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("10 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 10)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)

    timed(Some("10 threads")) {
      val copyTask = Paths.copyDirectories(origin, target, 10)(scheduler)
      Await.result(copyTask.runAsync(scheduler), Duration.Inf)
    }
    Paths.delete(target)
  }

  @Test
  def copyFilesTestIndependent(): Unit = {
    val units = 10
    val scheduler = Scheduler.io()
    val origin = AbsolutePath(System.getProperty("user.home"))
      .resolve("Code")
      .resolve("bloop")
      .resolve("backend")
      .resolve("target")

    val label: String = if (units == 0) "sequential" else s"${units} threads"

    for (i <- 0 to 20) {
      val target = AbsolutePath(Files.createTempDirectory("copy-parallel-benchmark"))
      println(s"target ${target}")
      timed(Some(label)) {
        if (units == 0) Paths.copyDirectoriesSequentially(origin, target)
        else {
          val copyTask = Paths.copyDirectories(origin, target, units)(scheduler)
          Await.result(copyTask.runAsync(scheduler), Duration.Inf)
        }
      }
      Paths.delete(target)
    }
  }

  def timed[T](prefix: Option[String] = None)(op: => T): T = {
    val start = System.nanoTime()
    try op
    finally {
      val elapsed = (System.nanoTime() - start).toDouble / 1e6
      System.out.println(
        s"Elapsed ${prefix.map(s => s"($s)").getOrElse("")}: $elapsed ms"
      )
    }
  }
}
