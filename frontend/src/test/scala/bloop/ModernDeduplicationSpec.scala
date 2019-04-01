package bloop

import bloop.config.Config
import bloop.io.RelativePath
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
  // Use only TCP to run deduplication
  override val protocol = BspProtocol.Tcp

  private def random(baselineMs: Int, until: Int): FiniteDuration = {
    val ms = baselineMs + scala.util.Random.nextInt(until).toLong
    FiniteDuration(ms, TimeUnit.MILLISECONDS)
  }

  private def checkDeduplication(logger: RecordingLogger, isDeduplicated: Boolean): Unit = {
    val deduplicated = logger.debugs.exists(_.startsWith("Deduplicating compilation"))
    if (isDeduplicated) assert(deduplicated) else assert(!deduplicated)
  }

  test("three concurrent clients deduplicate compilation") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val logger1 = new RecordingLogger(ansiCodesSupported = false)
    val logger2 = new RecordingLogger(ansiCodesSupported = false)
    val logger3 = new RecordingLogger(ansiCodesSupported = false)
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

      val projects = List(build.macroProject, build.userProject)
      loadBspState(build.workspace, projects, logger3) { bspState =>
        val firstCompilation =
          compiledMacrosState
            .withLogger(logger1)
            .compileHandle(build.userProject)
        val secondCompilation =
          compiledMacrosState
            .withLogger(logger2)
            .compileHandle(build.userProject, Some(FiniteDuration(2, TimeUnit.SECONDS)))
        val thirdCompilation =
          bspState.compileHandle(
            build.userProject,
            Some(FiniteDuration(2, TimeUnit.SECONDS))
          )

        val firstCompiledState = waitInSeconds(firstCompilation, 10)(logger1.writeToFile("1"))
        // Wait only +- 200ms to check no extra compilation happens but there's time to finish
        val secondCompiledState = waitInMillis(secondCompilation, 200)(logger2.writeToFile("2"))
        val thirdCompiledState = waitInMillis(thirdCompilation, 200)(logger3.writeToFile("3"))

        assert(firstCompiledState.status == ExitStatus.Ok)
        assert(secondCompiledState.status == ExitStatus.Ok)
        assert(thirdCompiledState.status == ExitStatus.Ok)

        // We get the same class files in all their external directories
        assertValidCompilationState(firstCompiledState, projects)
        assertValidCompilationState(secondCompiledState, projects)
        assertValidCompilationState(thirdCompiledState, projects)
        assertSameExternalClassesDirs(secondCompiledState, firstCompiledState, projects)
        assertSameExternalClassesDirs(thirdCompiledState.toTestState, firstCompiledState, projects)

        checkDeduplication(logger2, isDeduplicated = true)
        checkDeduplication(logger3, isDeduplicated = true)

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

        import java.util.concurrent.TimeoutException
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

  test("deduplication removes invalidated class files from all external classes dirs") {
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

      val bspLogger = new RecordingLogger(ansiCodesSupported = false)
      val cliLogger = new RecordingLogger(ansiCodesSupported = false)

      val projects = List(build.macroProject, build.userProject)
      loadBspState(build.workspace, projects, bspLogger) { bspState =>
        val firstCompilation = bspState.compileHandle(build.userProject)
        val firstCliCompilation =
          compiledMacrosState
            .withLogger(cliLogger)
            .compileHandle(
              build.userProject,
              Some(FiniteDuration(2, TimeUnit.SECONDS))
            )

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(10, TimeUnit.SECONDS))

        // Wait only +- 200ms in both to check no extra compilation happens but there's time to finish
        val firstCliCompiledState =
          Await.result(firstCliCompilation, FiniteDuration(200, TimeUnit.MILLISECONDS))

        assert(firstCompiledState.status == ExitStatus.Ok)
        assert(firstCliCompiledState.status == ExitStatus.Ok)

        // We get the same class files in all their external directories
        assertValidCompilationState(firstCompiledState, projects)
        assertValidCompilationState(firstCliCompiledState, projects)
        assertSameExternalClassesDirs(
          firstCliCompiledState,
          firstCompiledState.toTestState,
          projects
        )

        checkDeduplication(cliLogger, isDeduplicated = true)

        assertNoDiff(
          firstCompiledState.lastDiagnostics(build.userProject),
          """#1: task start 2
            |  -> Msg: Compiling user (2 Scala sources)
            |  -> Data kind: compile-task
            |#1: task finish 2
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'user'
            |  -> Data kind: compile-report
        """.stripMargin
        )

        assertNoDiff(
          cliLogger.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
         """.stripMargin
        )

        object Sources {
          // A modified version of `User2` that instead renames to `User3`
          val `User2.scala` =
            """/main/scala/User2.scala
              |package user
              |
              |object User3 extends App {
              |  macros.SleepMacro.sleep()
              |}
            """.stripMargin
        }

        val `User2.scala` = build.userProject.srcFor("/main/scala/User2.scala")
        assertIsFile(writeFile(`User2.scala`, Sources.`User2.scala`))
        val secondCompilation = firstCompiledState.compileHandle(build.userProject)
        val secondCliCompilation =
          firstCliCompiledState
            .withLogger(cliLogger)
            .compileHandle(
              build.userProject,
              Some(FiniteDuration(700, TimeUnit.MILLISECONDS))
            )

        val secondCompiledState =
          Await.result(secondCompilation, FiniteDuration(5, TimeUnit.SECONDS))
        val secondCliCompiledState =
          Await.result(secondCliCompilation, FiniteDuration(200, TimeUnit.MILLISECONDS))

        assert(secondCompiledState.status == ExitStatus.Ok)
        assert(secondCliCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)
        assertValidCompilationState(secondCliCompiledState, projects)

        assertNonExistingCompileProduct(
          secondCompiledState.toTestState,
          build.userProject,
          RelativePath("User2.class")
        )

        assertNonExistingCompileProduct(
          secondCompiledState.toTestState,
          build.userProject,
          RelativePath("User2$.class")
        )

        assertNonExistingCompileProduct(
          secondCliCompiledState,
          build.userProject,
          RelativePath("User2.class")
        )

        assertNonExistingCompileProduct(
          secondCliCompiledState,
          build.userProject,
          RelativePath("User2$.class")
        )

        assertSameExternalClassesDirs(
          secondCompiledState.toTestState,
          secondCliCompiledState,
          projects
        )

        assertNoDiff(
          cliLogger.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
             |Compiling user (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          secondCompiledState.lastDiagnostics(build.userProject),
          """#2: task start 4
            |  -> Msg: Compiling user (1 Scala source)
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

  test("deduplication doesn't work if project definition changes") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    BuildUtil.testSlowBuild(logger) { build =>
      val state = new TestState(build.state)
      val projects = List(build.macroProject, build.userProject)
      val testBuild = new TestBuild(state, projects)
      val compiledMacrosState = state.compile(build.macroProject)

      assert(compiledMacrosState.status == ExitStatus.Ok)
      assertValidCompilationState(compiledMacrosState, List(build.macroProject))
      assertNoDiff(
        logger.compilingInfos.mkString(System.lineSeparator()),
        s"""
           |Compiling macros (1 Scala source)
         """.stripMargin
      )

      val bspLogger = new RecordingLogger(ansiCodesSupported = false)
      val cliLogger = new RecordingLogger(ansiCodesSupported = false)

      loadBspState(build.workspace, projects, bspLogger) { bspState =>
        val firstCompilation = bspState.compileHandle(build.userProject)
        val firstCliCompilation = {
          /*
          val changeUserDefinition = Task {
            val userConfig = build.userProject.config
            userConfig.writeFile(testBuild.configFileFor(build.userProject))
          }
           */

          compiledMacrosState
            .withLogger(cliLogger)
            .compileHandle(
              build.userProject,
              Some(FiniteDuration(2, TimeUnit.SECONDS))
            )
        }

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(10, TimeUnit.SECONDS))

        // Wait only +- 200ms in both to check no extra compilation happens but there's time to finish
        val firstCliCompiledState =
          Await.result(firstCliCompilation, FiniteDuration(200, TimeUnit.MILLISECONDS))

        assert(firstCompiledState.status == ExitStatus.Ok)
        assert(firstCliCompiledState.status == ExitStatus.Ok)

        // We get the same class files in all their external directories
        assertValidCompilationState(firstCompiledState, projects)
        assertValidCompilationState(firstCliCompiledState, projects)
        assertSameExternalClassesDirs(
          firstCliCompiledState,
          firstCompiledState.toTestState,
          projects
        )

        checkDeduplication(cliLogger, isDeduplicated = true)

        assertNoDiff(
          firstCompiledState.lastDiagnostics(build.userProject),
          """#1: task start 2
            |  -> Msg: Compiling user (2 Scala sources)
            |  -> Data kind: compile-task
            |#1: task finish 2
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'user'
            |  -> Data kind: compile-report
        """.stripMargin
        )

        assertNoDiff(
          cliLogger.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
         """.stripMargin
        )

        object Sources {
          // A modified version of `User2` that instead renames to `User3`
          val `User2.scala` =
            """/main/scala/User2.scala
              |package user
              |
              |object User3 extends App {
              |  macros.SleepMacro.sleep()
              |}
            """.stripMargin
        }

        val `User2.scala` = build.userProject.srcFor("/main/scala/User2.scala")
        assertIsFile(writeFile(`User2.scala`, Sources.`User2.scala`))
        val secondCompilation = firstCompiledState.compileHandle(build.userProject)
        val secondCliCompilation =
          firstCliCompiledState
            .withLogger(cliLogger)
            .compileHandle(
              build.userProject,
              Some(FiniteDuration(700, TimeUnit.MILLISECONDS))
            )

        val secondCompiledState =
          Await.result(secondCompilation, FiniteDuration(5, TimeUnit.SECONDS))
        val secondCliCompiledState =
          Await.result(secondCliCompilation, FiniteDuration(200, TimeUnit.MILLISECONDS))

        assert(secondCompiledState.status == ExitStatus.Ok)
        assert(secondCliCompiledState.status == ExitStatus.Ok)
        assertValidCompilationState(secondCompiledState, projects)
        assertValidCompilationState(secondCliCompiledState, projects)

        assertNonExistingCompileProduct(
          secondCompiledState.toTestState,
          build.userProject,
          RelativePath("User2.class")
        )

        assertNonExistingCompileProduct(
          secondCompiledState.toTestState,
          build.userProject,
          RelativePath("User2$.class")
        )

        assertNonExistingCompileProduct(
          secondCliCompiledState,
          build.userProject,
          RelativePath("User2.class")
        )

        assertNonExistingCompileProduct(
          secondCliCompiledState,
          build.userProject,
          RelativePath("User2$.class")
        )

        assertSameExternalClassesDirs(
          secondCompiledState.toTestState,
          secondCliCompiledState,
          projects
        )

        assertNoDiff(
          cliLogger.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
             |Compiling user (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          secondCompiledState.lastDiagnostics(build.userProject),
          """#2: task start 4
            |  -> Msg: Compiling user (1 Scala source)
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
            |package macros
            |
            |import scala.reflect.macros.blackbox.Context
            |import scala.language.experimental.macros
            |
            |object SleepMacro {
            |  def sleep(): Unit = macro sleepImpl
            |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
            |    import c.universe._
            |    Thread.sleep(500)
            |    reify { () }
            |  }
            |}""".stripMargin

        val `B.scala` =
          """/B.scala
            |object B {
            |  def foo(s: String): String = s.toString
            |}
          """.stripMargin

        // Second (non-compiling) version of `B`
        val `B2.scala` =
          """/B.scala
            |object B {
            |  macros.SleepMacro.sleep()
            |  def foo(i: Int): String = i
            |}
          """.stripMargin

        // Third (compiling) slow version of `B`
        val `B3.scala` =
          """/B.scala
            |object B {
            |  macros.SleepMacro.sleep()
            |  def foo(s: String): String = s.toString
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
        val firstDelay = Some(random(200, 100))
        val secondDelay = Some(random(200, 200))
        val firstCompilation = bspState.compileHandle(`B`)
        val secondCompilation = compiledState.withLogger(cliLogger1).compileHandle(`B`, firstDelay)
        val thirdCompilation = compiledState.withLogger(cliLogger2).compileHandle(`B`, secondDelay)

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(2, TimeUnit.SECONDS))
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

        checkDeduplication(bspLogger, isDeduplicated = false)
        checkDeduplication(cliLogger1, isDeduplicated = true)
        checkDeduplication(cliLogger2, isDeduplicated = true)

        // We reproduce the same streaming side effects during compilation
        assertNoDiff(
          firstCompiledState.lastDiagnostics(`B`),
          """#1: task start 2
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#1: b/src/B.scala
            |  -> List(Diagnostic(Range(Position(2,28),Position(2,28)),Some(Error),None,None,type mismatch;  found   : Int  required: String,None))
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
            |[E1] b/src/B.scala:3:29
            |     type mismatch;
            |      found   : Int
            |      required: String
            |     L3:   def foo(i: Int): String = i
            |                                     ^
            |b/src/B.scala: L3 [E1]
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
          """[E1] b/src/B.scala:3:29
            |     type mismatch;
            |      found   : Int
            |      required: String
            |     L3:   def foo(i: Int): String = i
            |                                     ^
            |b/src/B.scala: L3 [E1]
            |'b' failed to compile.""".stripMargin
        )

        /* Repeat the same but this time the CLI client runs the compilation first */

        val cliLogger4 = new RecordingLogger(ansiCodesSupported = false)
        val cliLogger5 = new RecordingLogger(ansiCodesSupported = false)

        val fourthCompilation = thirdCompiledState.withLogger(cliLogger4).compileHandle(`B`)
        val fifthCompilation =
          thirdCompiledState.withLogger(cliLogger5).compileHandle(`B`, firstDelay)
        val sixthCompilation = bspState.compileHandle(`B`, secondDelay)

        val fourthCompiledState =
          Await.result(fourthCompilation, FiniteDuration(2, TimeUnit.SECONDS))
        val fifthCompiledState =
          Await.result(fifthCompilation, FiniteDuration(100, TimeUnit.MILLISECONDS))
        val secondBspState =
          Await.result(sixthCompilation, FiniteDuration(100, TimeUnit.MILLISECONDS))

        checkDeduplication(cliLogger4, isDeduplicated = false)
        checkDeduplication(bspLogger, isDeduplicated = true)
        checkDeduplication(cliLogger5, isDeduplicated = true)

        assertNoDiff(
          cliLogger4.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          cliLogger4.errors.mkString(System.lineSeparator()),
          """
            |[E1] b/src/B.scala:3:29
            |     type mismatch;
            |      found   : Int
            |      required: String
            |     L3:   def foo(i: Int): String = i
            |                                     ^
            |b/src/B.scala: L3 [E1]
            |'b' failed to compile.""".stripMargin
        )

        assertNoDiff(
          cliLogger5.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          cliLogger5.errors.mkString(System.lineSeparator()),
          """[E1] b/src/B.scala:3:29
            |     type mismatch;
            |      found   : Int
            |      required: String
            |     L3:   def foo(i: Int): String = i
            |                                     ^
            |b/src/B.scala: L3 [E1]
            |'b' failed to compile.""".stripMargin
        )

        assertNoDiff(
          secondBspState.lastDiagnostics(`B`),
          """#2: task start 4
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#2: b/src/B.scala
            |  -> List(Diagnostic(Range(Position(2,28),Position(2,28)),Some(Error),None,None,type mismatch;  found   : Int  required: String,None))
            |  -> reset = true
            |#2: task finish 4
            |  -> errors 1, warnings 0
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report
        """.stripMargin
        )

        writeFile(`B`.srcFor("B.scala"), Sources.`B3.scala`)

        val cliLogger7 = new RecordingLogger(ansiCodesSupported = false)

        /*
         * Repeat the same (the CLI client drives the compilation first) but
         * this time the latest result in cli client differs with that of the
         * BSP client. This test proves that our deduplication logic uses
         * client-specific data to populate reporters that then consume the
         * stream of compilation events.
         */

        val newCliCompiledState = {
          val underlyingState = fifthCompiledState.withLogger(cliLogger7).state
          val newPreviousResults = Map(
            // Use empty, remember we don't change last successful so incrementality works
            fifthCompiledState.getProjectFor(`B`) -> Compiler.Result.Empty
          )
          val newResults = underlyingState.results.replacePreviousResults(newPreviousResults)
          new TestState(underlyingState.copy(results = newResults))
        }

        val seventhCompilation = newCliCompiledState.compileHandle(`B`)
        val eigthCompilation = secondBspState.compileHandle(`B`, secondDelay)

        val seventhCompiledState =
          Await.result(seventhCompilation, FiniteDuration(2, TimeUnit.SECONDS))
        val thirdBspState =
          Await.result(eigthCompilation, FiniteDuration(100, TimeUnit.MILLISECONDS))

        checkDeduplication(cliLogger7, isDeduplicated = false)
        checkDeduplication(bspLogger, isDeduplicated = true)

        assertNoDiff(
          cliLogger7.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assert(cliLogger7.errors.size == 0)

        assertNoDiff(
          secondBspState.lastDiagnostics(`B`),
          """#3: task start 6
            |  -> Msg: Compiling b (1 Scala source)
            |  -> Data kind: compile-task
            |#3: b/src/B.scala
            |  -> List()
            |  -> reset = true
            |#3: task finish 6
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'b'
            |  -> Data kind: compile-report
        """.stripMargin
        )
      }
    }
  }

  test("cancel deduplicated compilation finishes all clients") {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |package macros
            |
            |import scala.reflect.macros.blackbox.Context
            |import scala.language.experimental.macros
            |
            |object SleepMacro {
            |  def sleep(): Unit = macro sleepImpl
            |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
            |    import c.universe._
            |    Thread.sleep(1000)
            |    reify { () }
            |  }
            |}""".stripMargin

        val `B.scala` =
          """/B.scala
            |object B {
            |  def foo(s: String): String = s.toString
            |}
          """.stripMargin

        val `B2.scala` =
          """/B.scala
            |object B {
            |  macros.SleepMacro.sleep()
            |  def foo(s: String): String = s.toString
            |}
          """.stripMargin
      }

      val cliLogger = new RecordingLogger(ansiCodesSupported = false)
      val bspLogger = new RecordingLogger(ansiCodesSupported = false)

      val `A` = TestProject(workspace, "a", List(Sources.`A.scala`))
      val `B` = TestProject(workspace, "b", List(Sources.`B.scala`), List(`A`))

      val projects = List(`A`, `B`)
      val state = loadState(workspace, projects, cliLogger)
      val compiledState = state.compile(`B`)

      writeFile(`B`.srcFor("B.scala"), Sources.`B2.scala`)

      loadBspState(workspace, projects, bspLogger) { bspState =>
        val firstDelay = Some(random(200, 100))
        val firstCompilation = bspState.compileHandle(`B`)
        val secondCompilation = compiledState.withLogger(cliLogger).compileHandle(`B`, firstDelay)

        ExecutionContext.ioScheduler.scheduleOnce(
          400,
          TimeUnit.MILLISECONDS,
          new Runnable {
            override def run(): Unit = firstCompilation.cancel()
          }
        )

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(3, TimeUnit.SECONDS))
        val secondCompiledState =
          Await.result(secondCompilation, FiniteDuration(100, TimeUnit.MILLISECONDS))

        assert(firstCompiledState.status == ExitStatus.CompilationError)
        assertCancelledCompilation(firstCompiledState.toTestState, List(`B`))
        assertNoDiff(
          bspLogger.infos.filterNot(_.contains("tcp")).mkString(System.lineSeparator()),
          """
            |request received: build/initialize
            |BSP initialization handshake complete.
            |Cancelling request "5"
          """.stripMargin
        )

        assert(secondCompiledState.status == ExitStatus.CompilationError)
        assertCancelledCompilation(secondCompiledState, List(`B`))

        checkDeduplication(bspLogger, isDeduplicated = false)
        checkDeduplication(cliLogger, isDeduplicated = true)

        assertNoDiff(
          firstCompiledState.lastDiagnostics(`B`),
          s"""
             |#1: task start 2
             |  -> Msg: Compiling b (1 Scala source)
             |  -> Data kind: compile-task
             |#1: task finish 2
             |  -> errors 0, warnings 0
             |  -> Msg: Compiled 'b'
             |  -> Data kind: compile-report
           """.stripMargin
        )

        assertNoDiff(
          cliLogger.warnings.mkString(System.lineSeparator()),
          "Cancelling compilation of b"
        )
      }
    }
  }
}
