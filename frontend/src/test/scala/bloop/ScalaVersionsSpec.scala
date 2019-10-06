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
        val (compilerOrg, compilerArtifact) = {
          if (scalaVersion.startsWith("0.")) "ch.epfl.lamp" -> "dotty-compiler_0.20"
          else "org.scala-lang" -> "scala-compiler"
        }

        def jarsForScalaVersion(version: String, logger: RecordingLogger) = {
          ScalaInstance
            .resolve(compilerOrg, compilerArtifact, version, logger)(ExecutionContext.ioScheduler)
            .allJars
            .map(AbsolutePath(_))
        }

        val source = {
          if (compilerArtifact.contains("dotty-compiler")) {
            """/main/scala/Foo.scala
              |class Foo { val x: String | Int = 1 }
            """.stripMargin
          } else {
            """/main/scala/Foo.scala
              |class Foo
            """.stripMargin
          }
        }

        val logger = new RecordingLogger(ansiCodesSupported = false)
        val jars = jarsForScalaVersion(scalaVersion, logger)
        val `A` = TestProject(
          workspace,
          "a",
          List(source),
          scalaOrg = Some(compilerOrg),
          scalaCompiler = Some(compilerArtifact),
          scalaVersion = Some(scalaVersion),
          jars = jars
        )

        val projects = List(`A`)
        val state = loadState(workspace, projects, logger)
        val compiledState = state.compile(`A`)
        Predef.assert(compiledState.status == ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
      }
    }

    val `2.10` = compileProjectFor("2.10.7")
    val `2.11` = compileProjectFor("2.11.12")
    val `2.12` = compileProjectFor("2.12.9")
    val `2.13` = compileProjectFor("2.13.0")
    val LatestDotty = compileProjectFor("0.20.0-bin-20191005-d67af24-NIGHTLY")
    val all = {
      if (TestUtil.isJdk8) List(`2.10`, `2.11`, `2.12`, `2.13`, LatestDotty)
      else List(`2.12`, `2.13`)
    }

    TestUtil.await(FiniteDuration(100, "s"), ExecutionContext.ioScheduler) {
      Task.sequence(all).map(_ => ())
    }
  }
}
