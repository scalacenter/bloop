package bloop

import bloop.config.Config
import bloop.logging.RecordingLogger
import bloop.cli.{Commands, ExitStatus, BspProtocol}
import bloop.engine.ExecutionContext
import bloop.util.{TestProject, TestUtil, BuildUtil}

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.CancelableFuture

object ModernDeduplicationSpec extends bloop.bsp.BspBaseSuite {
  // Use TCP because it's the only one working in Windows
  override val protocol = BspProtocol.Tcp

  /*
  test("deduplicate compilation in BSP and CLI clients") {
    val cliLogger = new RecordingLogger(ansiCodesSupported = false)
    val bspLogger = new RecordingLogger(ansiCodesSupported = false)
    BuildUtil.testSlowBuild(cliLogger) { build =>
      import build.workspace
      val state = new TestState(build.state)
      val projects = List(build.userProject, build.macroProject)

      val compiledMacrosState = state.compile(build.macroProject)
      assert(compiledMacrosState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledMacrosState, List(build.macroProject))

      def waitTimeWithin250msOf(baselineMs: Int) = {
        baselineMs + scala.util.Random.nextInt(250).toLong
      }

      loadBspState(workspace, projects, bspLogger) { bspState =>
        val cliState = compiledMacrosState
        val firstCompilation = cliState.compileHandle(build.userProject)

        // Start second compilation in a delay range of 500 + random(250) ms
        val bspWaitTimeMs = waitTimeWithin250msOf(500)
        val secondCompilation =
          bspState.compileHandle(build.userProject, Some(FiniteDuration(bspWaitTimeMs, "ms")))

      }
    }
  }
   */

  private def random(baselineMs: Int, until: Int): FiniteDuration = {
    val ms = baselineMs + scala.util.Random.nextInt(until).toLong
    FiniteDuration(ms, TimeUnit.MILLISECONDS)
  }

  ignore("three concurrent clients deduplicate compilation") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    BuildUtil.testSlowBuild(logger) { build =>
      val state = new TestState(build.state)
      val compiledMacrosState = state.compile(build.macroProject)
      assert(compiledMacrosState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledMacrosState, List(build.macroProject))
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator()),
        s"""
           |Compiling macros (1 Scala source)
         """.stripMargin
      )

      val logger1 = new RecordingLogger(ansiCodesSupported = false)
      val logger2 = new RecordingLogger(ansiCodesSupported = false)
      val logger3 = new RecordingLogger(ansiCodesSupported = false)

      val projects = List(build.macroProject, build.userProject)
      loadBspState(build.workspace, projects, logger3) { bspState =>
        val firstCompilation =
          compiledMacrosState
            .withLogger(logger1)
            .compileHandle(build.userProject)
        val secondCompilation =
          compiledMacrosState
            .withLogger(logger2)
            .compileHandle(build.userProject, Some(FiniteDuration(3, TimeUnit.SECONDS)))
        val thirdCompilation =
          bspState.compileHandle(
            build.userProject,
            Some(FiniteDuration(2, TimeUnit.SECONDS))
          )

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(10, TimeUnit.SECONDS))

        // Wait only +- 200ms in both to check no extra compilation happens but there's time to finish
        val secondCompiledState =
          Await.result(secondCompilation, FiniteDuration(200, TimeUnit.MILLISECONDS))
        val thirdCompiledState =
          Await.result(thirdCompilation, FiniteDuration(200, TimeUnit.MILLISECONDS))

        assert(firstCompiledState.status == ExitStatus.Ok)
        assert(secondCompiledState.status == ExitStatus.Ok)
        assert(thirdCompiledState.status == ExitStatus.Ok)

        // We get the same class files in all their external directories
        assertValidCompilationState(firstCompiledState, projects)
        assertValidCompilationState(secondCompiledState, projects)
        assertValidCompilationState(thirdCompiledState, projects)
        assertSameExternalClassesDirs(secondCompiledState, firstCompiledState, projects)
        assertSameExternalClassesDirs(thirdCompiledState.toTestState, firstCompiledState, projects)

        // We reproduce the same streaming side effects during compilation
        assertNoDiff(
          logger1.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
         """.stripMargin
        )

        assertNoDiff(
          logger2.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
         """.stripMargin
        )

        assertNoDiff(
          thirdCompiledState.lastDiagnostics(build.userProject),
          """#1: task start 2
            |  -> Msg: Compiling user (2 Scala sources)
            |  -> Data kind: compile-task
            |#1: task finish 2
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'user'
            |  -> Data kind: compile-report
        """.stripMargin
        )

        val delayFirstNoop = Some(random(0, 20))
        val delaySecondNoop = Some(random(0, 20))
        val noopCompiles = Task.mapBoth(
          Task.fromFuture(thirdCompiledState.compileHandle(build.userProject, delayFirstNoop)),
          Task.fromFuture(secondCompiledState.compileHandle(build.userProject, delaySecondNoop))
        ) {
          case states => states
        }

        val (firstNoopState, secondNoopState) = TestUtil.blockOnTask(noopCompiles, 1)
        assert(firstNoopState.status == ExitStatus.Ok)
        assert(secondNoopState.status == ExitStatus.Ok)
        assertValidCompilationState(firstNoopState, projects)
        assertValidCompilationState(secondNoopState, projects)
        assertSameExternalClassesDirs(firstNoopState.toTestState, secondNoopState, projects)
        assertSameExternalClassesDirs(firstNoopState, thirdCompiledState, projects)

        // A no-op doesn't output anything
        assertNoDiff(
          logger2.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
         """.stripMargin
        )

        assertNoDiff(
          thirdCompiledState.lastDiagnostics(build.userProject),
          """#2: task start 4
            |  -> Msg: Start no-op compilation for user
            |  -> Data kind: compile-task
            |#2: task finish 4
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'user'
            |  -> Data kind: compile-report
        """.stripMargin
        )
      }
    }
  }

  test("three concurrent clients receive error diagnostics appropriately") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |class A {
            |  println("Dummy class")
            |}
          """.stripMargin

        val `B.scala` =
          """/B.scala
            |object B {
            |  def foo(s: String): String = s.toString
            |}
          """.stripMargin

        // Second (non-compiling) version of `A`
        val `B2.scala` =
          """/B.scala
            |object B {
            |  def foo(i: Int): String = i
            |}
          """.stripMargin
      }

      val cliLogger1 = new RecordingLogger(ansiCodesSupported = false)
      val cliLogger2 = new RecordingLogger(ansiCodesSupported = false)
      val bspLogger = new RecordingLogger(ansiCodesSupported = false)

      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))

      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, cliLogger1)
      val compiledState = state.compile(`B`)

      writeFile(`B`.srcFor("B.scala"), Sources.`B2.scala`)

      loadBspState(workspace, projects, bspLogger) { bspState =>
        val firstDelay = Some(random(0, 100))
        val secondDelay = Some(random(0, 200))
        val firstCompilation = bspState.compileHandle(`B`)
        val thirdCompilation = compiledState.withLogger(cliLogger1).compileHandle(`B`, secondDelay)
        val secondCompilation = compiledState.withLogger(cliLogger2).compileHandle(`B`, firstDelay)

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(3, TimeUnit.SECONDS))

        // Wait only +- 200ms in both to check no extra compilation happens but there's time to finish
        val secondCompiledState =
          Await.result(secondCompilation, FiniteDuration(100, TimeUnit.MILLISECONDS))
        val thirdCompiledState =
          Await.result(thirdCompilation, FiniteDuration(100, TimeUnit.MILLISECONDS))

        assert(firstCompiledState.status == ExitStatus.CompilationError)
        assert(secondCompiledState.status == ExitStatus.CompilationError)
        assert(thirdCompiledState.status == ExitStatus.CompilationError)

        // Check we get the same class files in all their external directories
        assertInvalidCompilationState(
          firstCompiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          // Classes dir of BSP session is empty because no successful compilation happened
          hasSameContentsInClassesDir = false
        )

        assertInvalidCompilationState(
          secondCompiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )

        assertInvalidCompilationState(
          thirdCompiledState,
          projects,
          existsAnalysisFile = true,
          hasPreviousSuccessful = true,
          hasSameContentsInClassesDir = true
        )

        assertSameExternalClassesDirs(secondCompiledState, thirdCompiledState, projects)

        // We reproduce the same streaming side effects during compilation
        assertNoDiff(
          firstCompiledState.lastDiagnostics(`B`),
          """#1: task start 2
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#1: b/src/B.scala
            |  -> List(Diagnostic(Range(Position(1,28),Position(1,28)),Some(Error),None,None,type mismatch;  found   : Int  required: String,None))
            |  -> reset = true
            |#1: task finish 2
            |  -> errors 1, warnings 0
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report
        """.stripMargin
        )

        assertNoDiff(
          cliLogger1.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling a (1 Scala source)
             |Compiling b (1 Scala source)
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          cliLogger1.errors.mkString(System.lineSeparator()),
          """
            |[E1] b/src/B.scala:2:29
            |     type mismatch;
            |      found   : Int
            |      required: String
            |     L2:   def foo(i: Int): String = i
            |                                     ^
            |b/src/B.scala: L2 [E1]
            |'b' failed to compile.""".stripMargin
        )

        assertNoDiff(
          cliLogger2.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          cliLogger2.errors.mkString(System.lineSeparator()),
          """[E1] b/src/B.scala:2:29
            |     type mismatch;
            |      found   : Int
            |      required: String
            |     L2:   def foo(i: Int): String = i
            |                                     ^
            |b/src/B.scala: L2 [E1]
            |'b' failed to compile.""".stripMargin
        )
      }
    }
  }
}
