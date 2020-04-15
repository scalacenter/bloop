package bloop

import bloop.testing.BaseSuite
import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.cli.ExitStatus
import coursier.paths.CoursierPaths
import scala.util.Properties
import java.nio.file.Paths
import bloop.io.AbsolutePath

object ConsoleSpec extends BaseSuite {
  test("default ammonite console works in multi-build project") {
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
      val ammArgs = List("--no-home-predef")
      val compiledState = state.console(`B`, ammArgs)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val cache = CoursierPaths.cacheDirectory()
      val workspacePath = AbsolutePath(workspace.underlying.toRealPath())

      val projectB = state.getProjectFor(`B`)
      val dagB = state.getDagFor(`B`)
      val classpathB = projectB.fullRuntimeClasspath(dagB, state.client)
      val coursierClasspathArgs =
        classpathB.flatMap(elem => Seq("--extra-jars", elem.syntax))
      val expectedCommand =
        s"coursier launch com.lihaoyi:ammonite_2.12.8:latest.release --main-class ammonite.Main --scala-version 2.12.8 ${coursierClasspathArgs
          .mkString(" ")} ${("--" :: ammArgs).mkString(" ")}"

      assertNoDiff(
        logger.captureTimeInsensitiveInfos
          .filterNot(
            msg =>
              msg == "" || msg.startsWith("Non-compiled module") || msg
                .startsWith(" Compilation completed in")
          )
          .mkString(System.lineSeparator()),
        s"""|Compiling a (1 Scala source)
            |Compiled a ???
            |Compiling b (1 Scala source)
            |Compiled b ???
            |$expectedCommand
            |""".stripMargin
      )
    }
  }
}
