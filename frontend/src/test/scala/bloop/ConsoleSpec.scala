package bloop

import bloop.testing.BaseSuite
import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.cli.ExitStatus
import coursier.CoursierPaths
import coursier.Cache
import scala.util.Properties
import java.nio.file.Paths

object ConsoleSpec extends BaseSuite {
  test("console works") {
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
      val compiledState = state.console(`B`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      val cache = Paths.get(Properties.userHome, ".cache", "coursier", "v1")
      val expectedCommand = s"coursier launch com.lihaoyi:ammonite_2.12.8:latest.release --main-class ammonite.Main --extra-jars ${workspace.syntax}/resources --extra-jars ${workspace.syntax}/target/b/classes --extra-jars ${workspace.syntax}/target/a/classes --extra-jars $cache/https/repo1.maven.org/maven2/org/scala-lang/scala-library/2.12.8/scala-library-2.12.8.jar --extra-jars $cache/https/repo1.maven.org/maven2/org/scala-lang/scala-compiler/2.12.8/scala-compiler-2.12.8.jar --extra-jars $cache/https/repo1.maven.org/maven2/org/scala-lang/modules/scala-xml_2.12/1.0.6/scala-xml_2.12-1.0.6.jar --extra-jars $cache/https/repo1.maven.org/maven2/org/scala-lang/scala-reflect/2.12.8/scala-reflect-2.12.8.jar"
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
