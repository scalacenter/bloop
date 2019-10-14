package bloop.io

import bloop.logging.RecordingLogger
import scala.concurrent.Promise
import bloop.util.TestUtil
import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import bloop.util.TestProject

object SourceHasherSpec extends bloop.testing.BaseSuite {
  test("cancellation works") {
    val largeFileContents = {
      val sb = new StringBuilder()
      var base = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      for (i <- 0 to 4000) {
        sb.++=(base)
      }
      sb.result()
    }

    TestUtil.withinWorkspace { workspace =>
      import bloop.engine.ExecutionContext.ioScheduler
      val logger = new RecordingLogger()
      val cancelPromise = Promise[Unit]()
      val cancelPromise2 = Promise[Unit]()
      val sources = {
        var allSources: List[String] = Nil
        for (i <- 0 to 200) {
          val contents = s"/A$i.scala${System.lineSeparator}$largeFileContents"
          allSources = contents :: allSources
        }
        allSources
      }

      val `A` = TestProject(workspace, "a", sources)
      val state = loadState(workspace, List(`A`), logger)
      val projectA = state.getProjectFor(`A`)

      val sourceHashesTask =
        SourceHasher.findAndHashSourcesInProject(projectA, 2, cancelPromise, ioScheduler)
      val running = sourceHashesTask.runAsync(ioScheduler)

      Thread.sleep(2)
      running.cancel()

      val cancelledResult = Await.result(running, FiniteDuration(20, "s"))
      assert(cancelPromise.isCompleted)
      assert(cancelledResult.isLeft)

      val sourceHashesTask2 =
        SourceHasher.findAndHashSourcesInProject(projectA, 2, cancelPromise2, ioScheduler)
      val running2 = sourceHashesTask2.runAsync(ioScheduler)
      val uncancelledResult = Await.result(running2, FiniteDuration(20, "s"))
      assert(uncancelledResult.isRight)
      assert(uncancelledResult.forall(_.nonEmpty))
      assert(!cancelPromise2.isCompleted)
    }
  }
}
