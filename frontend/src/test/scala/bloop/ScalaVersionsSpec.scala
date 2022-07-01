package bloop
import scala.concurrent.duration.FiniteDuration

import bloop.cli.ExitStatus
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.util.TestUtil

import monix.eval.Task

object ScalaVersionsSpec extends bloop.testing.BaseSuite {

  def compileProjectFor(scalaVersion: String, logger: RecordingLogger): Task[Unit] = Task {
    TestUtil.withinWorkspace { workspace =>
      val (compilerOrg, compilerArtifact) = {
        if (scalaVersion.startsWith("3."))
          "org.scala-lang" -> "scala3-compiler_3"
        else "org.scala-lang" -> "scala-compiler"
      }

      def jarsForScalaVersion(version: String, logger: RecordingLogger) = {
        ScalaInstance
          .resolve(compilerOrg, compilerArtifact, version, logger)
          .allJars
          .map(AbsolutePath(_))
      }

      val source = {
        if (compilerArtifact.contains("scala3-compiler")) {
          """/main/scala/Foo.scala
            |class Foo { val x: String | Int = 1 }
            """.stripMargin
        } else {
          """/main/scala/Foo.scala
            |class Foo
            """.stripMargin
        }
      }

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

  val jdk8OnlyVersions = List(
    "2.10.7",
    "2.11.12"
  )
  val scalaVersions = List(
    "2.12.16",
    "2.13.6",
    "2.13.7",
    "2.13.8",
    "3.0.2",
    "3.1.3"
  )

  val allVersions = if (TestUtil.isJdk8) jdk8OnlyVersions ++ scalaVersions else scalaVersions

  allVersions.foreach { scalaV =>
    test(s"cross compile build to Scala version $scalaV") {
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val compiled = compileProjectFor(scalaV, logger)

      TestUtil.await(FiniteDuration(120, "s"), ExecutionContext.ioScheduler)(
        compiled.map(_ => ()).onErrorHandleWith { err =>
          Task
            .eval(logger.dump())
            .flatMap(_ => Task(TestUtil.threadDump))
            .flatMap(_ => Task.raiseError(err))

        }
      )
    }
  }

}
