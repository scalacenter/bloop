package bloop
import monix.eval.Task
import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.util.TestProject
import bloop.cli.ExitStatus
import scala.concurrent.duration.FiniteDuration

object ScalaVersionsSpec extends bloop.testing.BaseSuite {
  test("cross-compile build to latest Scala versions") {
    def compileProjectFor(scalaVersion: String): Task[Unit] = Task {
      TestUtil.withinWorkspace { workspace =>
        val sources = List(
          """/main/scala/Foo.scala
            |class Foo
          """.stripMargin
        )
        def jarsForScalaVersion(version: String, logger: RecordingLogger) = {
          ScalaInstance
            .resolve("org.scala-lang", "scala-compiler", version, logger)(
              ExecutionContext.ioScheduler
            )
            .allJars
            .map(AbsolutePath(_))
        }

        val logger = new RecordingLogger(ansiCodesSupported = false)
        val jars = jarsForScalaVersion(scalaVersion, logger)
        val `A` =
          TestProject(workspace, "a", sources, scalaVersion = Some(scalaVersion), jars = jars)
        val projects = List(`A`)
        val state = loadState(workspace, projects, logger)
        val compiledState = state.compile(`A`)
        assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
      }
    }

    val `2.10` = compileProjectFor("2.10.7")
    val `2.11` = compileProjectFor("2.11.11")
    val `2.12` = compileProjectFor("2.12.9")
    val `2.13` = compileProjectFor("2.13.0")
    val all = {
      if (TestUtil.isJdk8) List(`2.10`, `2.11`, `2.12`, `2.13`)
      else List(`2.12`, `2.13`)
    }

    TestUtil.await(FiniteDuration(100, "s"), ExecutionContext.ioScheduler) {
      Task.sequence(all).map(_ => ())
    }
  }
}
