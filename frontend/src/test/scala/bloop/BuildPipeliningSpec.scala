package bloop

import bloop.io.{AbsolutePath, RelativePath, Paths => BloopPaths}
import bloop.logging.RecordingLogger
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Feedback, Run, State}
import bloop.util.{TestProject, TestUtil}

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

object BuildPipeliningSpec extends bloop.testing.BaseSuite {
  test("compile build incrementally sourcing from an analysis file") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A
          """.stripMargin
        val `B.scala` =
          """/B.scala
            |class B extends A
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))
      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compileWithPipelining(`B`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }
}
