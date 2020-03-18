package bloop.io

import bloop.logging.RecordingLogger
import scala.concurrent.Promise
import bloop.util.TestUtil
import java.nio.file.Files
import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration
import bloop.util.TestProject

object SourceHasherSpec extends bloop.testing.BaseSuite {
  flakyTest("cancellation works", 3) {
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

  test("test that hidden source files are not matched") {
    // Hidden source files should not be matched. Editors such as emacs can
    // write hidden files to serve as backups, e.g. interlock files
    TestUtil.withinWorkspace { workspace =>
      val hiddenScalaFile = workspace.resolve(".Hello.scala")
      val hiddenJavaFile = workspace.resolve(".Hello.java")

      val validScalaFile = workspace.resolve("Hello.scala")
      val validJavaFile = workspace.resolve("Hello.java")

      val nestedDir = workspace.resolve("nested")
      val hiddenNestedJavaFile = nestedDir.resolve(".#Hello.java")
      val validNestedJavaFile = nestedDir.resolve("Hello.java")

      val nestedHiddenDir = workspace.resolve(".nested")
      val hiddenInHiddenNestedDirJavaFile = nestedHiddenDir.resolve(".#Hello.java")
      val validInHiddenNestedDirJavaFile = nestedHiddenDir.resolve("Hello.java")

      assert(!SourceHasher.matchSourceFile(hiddenScalaFile.underlying))
      assert(!SourceHasher.matchSourceFile(hiddenJavaFile.underlying))

      assert(SourceHasher.matchSourceFile(validScalaFile.underlying))
      assert(SourceHasher.matchSourceFile(validJavaFile.underlying))

      assert(!SourceHasher.matchSourceFile(hiddenNestedJavaFile.underlying))
      assert(SourceHasher.matchSourceFile(validNestedJavaFile.underlying))

      assert(!SourceHasher.matchSourceFile(hiddenInHiddenNestedDirJavaFile.underlying))
      assert(SourceHasher.matchSourceFile(validInHiddenNestedDirJavaFile.underlying))
    }
  }
}
