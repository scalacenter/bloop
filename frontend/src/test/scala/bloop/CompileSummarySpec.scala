package bloop

import bloop.cli.ExitStatus
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

object CompileSummarySpec extends bloop.testing.BaseSuite {
  private def summaryLines(logger: RecordingLogger): List[String] = {
    logger.captureTimeInsensitiveInfos.filter { line =>
      line.contains(" - ???") || line.contains(" - blocked on ") ||
      line.startsWith("Total module compile time:") || line.startsWith("Wall-clock duration:")
    }
  }

  test("print a duration summary for every compiled project when enabled") {
    TestUtil.withinWorkspace { workspace =>
      val sourcesA = List(
        """/main/scala/A.scala
          |object A { def n: Int = 1 }
          |""".stripMargin
      )
      val sourcesB = List(
        """/main/scala/B.scala
          |object B { def n: Int = A.n }
          |""".stripMargin
      )
      val sourcesC = List(
        """/main/scala/C.scala
          |object C { def n: Int = B.n }
          |""".stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sourcesA)
      val `B` = TestProject(workspace, "b", sourcesB, List(`A`))
      val `C` = TestProject(workspace, "c", sourcesC, List(`B`))
      val projects = List(`A`, `B`, `C`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compileWithSummary(`C`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      // Module lines are sorted slowest-first, which is nondeterministic here; compare sorted
      val (moduleLines, totalLines) = summaryLines(logger).partition(_.contains(" - ???"))
      assertNoDiff(
        (moduleLines.sorted ++ totalLines).mkString(lineSeparator),
        """|a - ???
           |b - ???
           |c - ???
           |Total module compile time: ???
           |Wall-clock duration: ???
           |""".stripMargin
      )
    }
  }

  test("print no summary by default") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/A.scala
          |object A { def n: Int = 1 }
          |""".stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assert(summaryLines(logger).isEmpty)
    }
  }

  test("print a duration summary when compilation fails") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/A.scala
          |object A { def n: Int = "" }
          |""".stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sources)
      val state = loadState(workspace, List(`A`), logger)
      val compiledState = state.compileWithSummary(`A`)
      assertExitStatus(compiledState, ExitStatus.CompilationError)
      assertNoDiff(
        summaryLines(logger).mkString(lineSeparator),
        """|a - ???(failed)
           |Total module compile time: ???
           |Wall-clock duration: ???
           |""".stripMargin
      )
    }
  }

  test("render blocked projects when an upstream project fails") {
    TestUtil.withinWorkspace { workspace =>
      val sourcesA = List(
        """/main/scala/A.scala
          |object A { def n: Int = "" }
          |""".stripMargin
      )
      val sourcesB = List(
        """/main/scala/B.scala
          |object B { def n: Int = 1 }
          |""".stripMargin
      )

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", sourcesA)
      val `B` = TestProject(workspace, "b", sourcesB, List(`A`))
      val state = loadState(workspace, List(`A`, `B`), logger)
      val compiledState = state.compileWithSummary(`B`)
      assertExitStatus(compiledState, ExitStatus.CompilationError)
      assertNoDiff(
        summaryLines(logger).mkString(lineSeparator),
        """|a - ???(failed)
           |b - blocked on a
           |Total module compile time: ???
           |Wall-clock duration: ???
           |""".stripMargin
      )

      // A repeated run produces an unchanged blocked result; it must still be reported
      logger.clear()
      val failedAgainState = compiledState.compileWithSummary(`B`)
      assertExitStatus(failedAgainState, ExitStatus.CompilationError)
      assertNoDiff(
        summaryLines(logger).mkString(lineSeparator),
        """|a - ???(failed)
           |b - blocked on a
           |Total module compile time: ???
           |Wall-clock duration: ???
           |""".stripMargin
      )
    }
  }
}
