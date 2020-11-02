package bloop
import monix.eval.Task
import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.util.TestProject
import bloop.cli.ExitStatus
import scala.concurrent.duration.FiniteDuration
import bloop.engine.State
import java.util.concurrent.TimeoutException
import monix.execution.misc.NonFatal
import java.lang.management.ManagementFactory

object ScalaVersionsSpec extends bloop.testing.BaseSuite {
  test("cross-compile build to latest Scala versions") {
    var loggers: List[RecordingLogger] = Nil
    def compileProjectFor(scalaVersion: String): Task[Unit] = Task {
      TestUtil.withinWorkspace { workspace =>
        val (compilerOrg, compilerArtifact) = {
          if (scalaVersion.startsWith("3.")) "org.scala-lang" -> "scala3-compiler_3.0.0-M2"
          else "org.scala-lang" -> "scala-compiler"
        }

        def jarsForScalaVersion(version: String, logger: RecordingLogger) = {
          ScalaInstance
            .resolve(compilerOrg, compilerArtifact, version, logger)(ExecutionContext.ioScheduler)
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

        val logger = new RecordingLogger(ansiCodesSupported = false)
        loggers.synchronized { loggers = logger :: loggers }
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
        try {
          Predef.assert(compiledState.status == ExitStatus.Ok)
          assertValidCompilationState(compiledState, projects)
        } finally loggers.synchronized { loggers = loggers.filterNot(_ == logger) }
      }
    }

    val `2.10` = compileProjectFor("2.10.7")
    val `2.11` = compileProjectFor("2.11.12")
    val `2.12` = compileProjectFor("2.12.9")
    val `2.13` = compileProjectFor("2.13.2")
    val `2.13.3` = compileProjectFor("2.13.3")
    val LatestDotty = compileProjectFor("3.0.0-M2-bin-20201031-1ab76c1-NIGHTLY")
    val all = {
      if (TestUtil.isJdk8) List(`2.10`, `2.11`, `2.12`, `2.13`, `2.13.3`, LatestDotty)
      else List(`2.12`, `2.13`, `2.13.3`)
    }

    try {
      TestUtil.await(FiniteDuration(120, "s"), ExecutionContext.ioScheduler) {
        Task
          .sequence(all.grouped(2).map(group => Task.gatherUnordered(group)))
          .map(_ => ())
      }
    } catch {
      case NonFatal(t) =>
        loggers.foreach(logger => logger.dump())
        Thread.sleep(100)
        System.err.println(TestUtil.threadDump)
        Thread.sleep(100)
        throw t
    } finally {}
  }
}
