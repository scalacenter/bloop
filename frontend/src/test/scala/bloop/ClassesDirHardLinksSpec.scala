package bloop

import java.nio.file.Files
import java.util.Arrays

import bloop.cli.ExitStatus
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

object ClassesDirHardLinksSpec extends bloop.testing.BaseSuite {

  object Sources {
    val `Foo.scala` =
      """/Foo.scala
        |class Foo
        """.stripMargin

    val `Bar.scala` =
      """/Bar.scala
        |class Bar
        """.stripMargin

    val `Bar2.scala` =
      """/Bar.scala
        |class Bar {
        |  def bar: Int = 2
        |}
        """.stripMargin
  }

  test("client classes dir shares class files with the read-only classes dir") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Foo.scala`, Sources.`Bar.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compile(`A`)
      assertExitStatus(compiledState, ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val firstReadOnlyDir = compiledState.getLastClassesDir(`A`).get
      val clientDir = compiledState.getClientExternalDir(`A`)
      assertLinked(firstReadOnlyDir, clientDir)
      val barBefore = Files.readAllBytes(clientDir.resolve("Bar.class").underlying)

      assertIsFile(writeFile(`A`.srcFor("Bar.scala"), Sources.`Bar2.scala`))
      val secondCompiledState = compiledState.compile(`A`)
      assertExitStatus(secondCompiledState, ExitStatus.Ok)
      assertValidCompilationState(secondCompiledState, projects)

      val secondReadOnlyDir = secondCompiledState.getLastClassesDir(`A`).get
      assert(secondReadOnlyDir != firstReadOnlyDir)
      // Covers both the recompiled `Bar.class` and `Foo.class`, refilled from the old dir
      assertLinked(secondReadOnlyDir, clientDir)
      val barAfter = Files.readAllBytes(clientDir.resolve("Bar.class").underlying)
      assert(!Arrays.equals(barBefore, barAfter))

      // `Foo.class` is only refilled if the second compile leaves `Foo.scala` alone
      assertNoDiff(
        logger.compilingInfos.mkString(lineSeparator),
        """Compiling a (2 Scala sources)
          |Compiling a (1 Scala source)""".stripMargin
      )
    }
  }
}
