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
import bloop.engine.ExecutionContext

object BuildPipeliningSpec extends bloop.testing.BaseSuite {
  test("compile simple build") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |package a
            |class A
          """.stripMargin
        val `B.scala` =
          """/B.scala
            |package b
            |class B extends a.A
          """.stripMargin
        val `C.scala` =
          """/C.scala
            |package c
            |class C extends b.B
          """.stripMargin
        val `D.scala` =
          """/D.scala
            |package d
            |class D extends c.C
          """.stripMargin
        val `E.scala` =
          """/E.scala
            |package e
            |class E extends d.D
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))
      val `C` = TestProject(workspace, "c", List(Sources.`C.scala`), List(`B`))
      val `D` = TestProject(workspace, "d", List(Sources.`D.scala`), List(`C`))
      val `E` = TestProject(workspace, "e", List(Sources.`E.scala`), List(`D`))
      val projects = List(`A`, `B`, `C`, `D`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compileWithPipelining(`D`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)
    }
  }

  test("compile simple build using Scala 2.10 (without pipelining)") {
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
      val jars = {
        ScalaInstance
          .resolve("org.scala-lang", "scala-compiler", "2.10.7", logger)(
            ExecutionContext.ioScheduler
          )
          .allJars
          .map(AbsolutePath(_))
      }

      val scalaV = Some("2.10.7")
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`A.scala`),
        scalaVersion = scalaV,
        jars = jars
      )

      val `B` = TestProject(
        workspace,
        "b",
        List(Sources.`B.scala`),
        List(`A`),
        scalaVersion = scalaV,
        jars = jars
      )

      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compileWithPipelining(`B`)
      assert(compiledState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledState, projects)

      assertNoDiff(
        logger.renderTimeInsensitiveInfos,
        """|Compiling a (1 Scala source)
           |Compiled a ???
           |Compiling b (1 Scala source)
           |Compiled b ???
           |""".stripMargin
      )

      assertNoDiff(
        logger.warnings.mkString(System.lineSeparator()),
        """|The project a didn't use pipelined compilation.
           |The project b didn't use pipelined compilation.
           |""".stripMargin
      )
    }
  }

  test("pipelining makes Java wait on upstream Scala compiles") {
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
        val `C.java` =
          """/C.java
            |public class C extends B {}
          """.stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`, Sources.`B.scala`))
      // A project in the middle of the dependency graph with no sources
      val `B` = TestProject(workspace, "b", Nil, List(`A`))
      val `C` = TestProject(workspace, "c", List(Sources.`C.java`), List(`B`))

      val projects = List(`A`, `B`, `C`)
      val state = loadState(workspace, projects, logger)
      val compiledState = state.compileWithPipelining(`C`)
      assert(compiledState.status == ExitStatus.Ok)
      // Only check valid state in `A` and `C` because `B` is empty!
      assertValidCompilationState(compiledState, List(`A`, `C`))
    }
  }
}
