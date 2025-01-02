package bloop.io

import java.nio.file.Files
import bloop.io.ParallelOps.CopyMode
import org.junit.Test
import bloop.logging.RecordingLogger
import bloop.task.Task
import monix.execution.Scheduler
import java.nio.file.StandardOpenOption
import scala.concurrent.duration._
import scala.concurrent.Await

class ParallelOpsSuite {

  private def createRandomDirectory() = {

    val from = Files.createTempDirectory("parallel")
    val inputFile = from.resolve("text.scala")
    val text = "random\n" * 100
    Files.write(inputFile, text.getBytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
  }
  @Test
  def runMultipleCopies() = {

    val from = createRandomDirectory()
    val to = Files.createTempDirectory("parallel")

    val config =
      ParallelOps.CopyConfiguration(5, CopyMode.ReplaceExisting, Set.empty, Set.empty)
    val logger = new RecordingLogger()

    val tasks =
      for (_ <- 0 to 100)
        yield ParallelOps
          .copyDirectories(config)(
            from,
            to,
            Scheduler.Implicits.global,
            enableCancellation = false,
            logger
          )

    val result = Task.gatherUnordered(tasks).runAsync(Scheduler.Implicits.global)
    val res = Await.result(result, 15.seconds)
    assert(res.size == 101)
  }

  @Test
  def runMultipleCopiesFromDifferentSource() = {

    val to = Files.createTempDirectory("parallel")

    val config =
      ParallelOps.CopyConfiguration(100, CopyMode.ReplaceExisting, Set.empty, Set.empty)
    val logger = new RecordingLogger()

    val tasks =
      for (_ <- 0 to 100)
        yield ParallelOps
          .copyDirectories(config)(
            createRandomDirectory(),
            to,
            Scheduler.Implicits.global,
            enableCancellation = false,
            logger
          )

    val result = Task.gatherUnordered(tasks).runAsync(Scheduler.Implicits.global)
    val res = Await.result(result, 15.seconds)
    assert(res.size == 101)
  }

}
