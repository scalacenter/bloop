package bloop

import bloop.config.Config
import bloop.io.RelativePath
import bloop.logging.RecordingLogger
import bloop.cli.{Commands, ExitStatus, BspProtocol}
import bloop.engine.ExecutionContext
import bloop.util.{TestProject, TestUtil, BuildUtil, SystemProperties}

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.Promise
import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.CancelableFuture

import bloop.data.ClientInfo.BspClientInfo
import bloop.data.ClientInfo.CliClientInfo

import ch.epfl.scala.{bsp => scalabsp}
import bloop.logging.Logger

object DeduplicationSpec extends bloop.bsp.BspBaseSuite {
  // Use only TCP to run deduplication
  override val protocol = BspProtocol.Tcp

  private def random(baselineMs: Int, until: Int): FiniteDuration = {
    val ms = baselineMs + scala.util.Random.nextInt(until).toLong
    FiniteDuration(ms, TimeUnit.MILLISECONDS)
  }

  private def checkDeduplication(logger: RecordingLogger, isDeduplicated: Boolean): Unit = {
    val deduplicated = logger.infos.exists(_.startsWith("Deduplicating compilation"))
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

      val compileStartPromises =
        new mutable.HashMap[scalabsp.BuildTargetIdentifier, Promise[Unit]]()
      val startedProjectCompilation = Promise[Unit]()
      compileStartPromises.put(build.userProject.bspId, startedProjectCompilation)

      val projects = List(build.macroProject, build.userProject)
      loadBspState(
        build.workspace,
        projects,
        logger1,
        compileStartPromises = Some(compileStartPromises)
      ) { bspState =>
        val firstCompilation = bspState.compileHandle(build.userProject)

        val secondCompilation = waitUntilStartAndCompile(
          compiledMacrosState,
          build.userProject,
          startedProjectCompilation,
          logger2
        )

        val thirdCompilation = waitUntilStartAndCompile(
          compiledMacrosState,
          build.userProject,
          startedProjectCompilation,
          logger3
        )

        val firstCompiledState = waitInSeconds(firstCompilation, 10)(logger1.writeToFile("1"))
        val (secondCompiledState, thirdCompiledState) =
          TestUtil.blockOnTask(mapBoth(secondCompilation, thirdCompilation), 3)

        assert(firstCompiledState.status == ExitStatus.Ok)
        assert(secondCompiledState.status == ExitStatus.Ok)
        assert(thirdCompiledState.status == ExitStatus.Ok)

        // We get the same class files in all their external directories
        assertValidCompilationState(firstCompiledState, projects)
        assertValidCompilationState(secondCompiledState, projects)
        assertValidCompilationState(thirdCompiledState, projects)
        assertSameExternalClassesDirs(
          secondCompiledState,
          firstCompiledState.toTestState,
          projects
        )
        assertSameExternalClassesDirs(
          thirdCompiledState,
          firstCompiledState.toTestState,
          projects
        )

        checkDeduplication(logger2, isDeduplicated = true)
        checkDeduplication(logger3, isDeduplicated = true)

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
          logger2.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
         """.stripMargin
        )

        assertNoDiff(
          logger3.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
         """.stripMargin
        )

        val delayFirstNoop = Some(random(0, 20))
        val delaySecondNoop = Some(random(0, 20))
        val noopCompiles = mapBoth(
          firstCompiledState.compileHandle(build.userProject, delayFirstNoop),
          secondCompiledState.compileHandle(build.userProject, delaySecondNoop)
        )

        import java.util.concurrent.TimeoutException
        val (firstNoopState, secondNoopState) = TestUtil.blockOnTask(noopCompiles, 2)

        assert(firstNoopState.status == ExitStatus.Ok)
        assert(secondNoopState.status == ExitStatus.Ok)
        assertValidCompilationState(firstNoopState, projects)
        assertValidCompilationState(secondNoopState, projects)
        assertSameExternalClassesDirs(firstNoopState.toTestState, secondNoopState, projects)
        assertSameExternalClassesDirs(firstNoopState.toTestState, thirdCompiledState, projects)

        assertNoDiff(
          firstCompiledState.lastDiagnostics(build.userProject),
          """#2: task start 4
            |  -> Msg: Start no-op compilation for user
            |  -> Data kind: compile-task
            |#2: task finish 4
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'user'
            |  -> Data kind: compile-report
        """.stripMargin
        )

        // Same check as before because no-op should not show any more input
        assertNoDiff(
          logger2.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling user (2 Scala sources)
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

      val compileStartPromises =
        new mutable.HashMap[scalabsp.BuildTargetIdentifier, Promise[Unit]]()
      val startedFirstCompilation = Promise[Unit]()
      compileStartPromises.put(build.userProject.bspId, startedFirstCompilation)

      val projects = List(build.macroProject, build.userProject)
      loadBspState(
        build.workspace,
        projects,
        bspLogger,
        compileStartPromises = Some(compileStartPromises)
      ) { bspState =>
        val firstCompilation = bspState.compileHandle(build.userProject)
        val firstCliCompilation =
          waitUntilStartAndCompile(
            compiledMacrosState,
            build.userProject,
            startedFirstCompilation,
            cliLogger
          )

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(10, TimeUnit.SECONDS))
        val firstCliCompiledState =
          Await.result(firstCliCompilation, FiniteDuration(1, TimeUnit.SECONDS))

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

        val startedSecondCompilation = Promise[Unit]()
        compileStartPromises.put(build.userProject.bspId, startedSecondCompilation)

        val `User2.scala` = build.userProject.srcFor("/main/scala/User2.scala")
        assertIsFile(writeFile(`User2.scala`, Sources.`User2.scala`))
        val secondCompilation = firstCompiledState.compileHandle(build.userProject)
        val secondCliCompilation =
          waitUntilStartAndCompile(
            firstCliCompiledState,
            build.userProject,
            startedSecondCompilation,
            cliLogger
          )

        val secondCompiledState =
          Await.result(secondCompilation, FiniteDuration(5, TimeUnit.SECONDS))
        val secondCliCompiledState =
          Await.result(secondCliCompilation, FiniteDuration(500, TimeUnit.MILLISECONDS))

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

      object Sources {
        val `User2.scala` =
          """/main/scala/User2.scala
            |package user
            |
            |object User2 extends App {
            |  // Should report warning with -Ywarn-numeric-widen
            |  val i: Long = 1.toInt
            |  macros.SleepMacro.sleep()
            |}
          """.stripMargin
      }

      writeFile(build.userProject.srcFor("/main/scala/User2.scala"), Sources.`User2.scala`)
      loadBspState(build.workspace, projects, bspLogger) { bspState =>
        val firstCompilation = bspState.compileHandle(build.userProject)

        val changeOpts = (s: Config.Scala) => s.copy(options = "-Ywarn-numeric-widen" :: s.options)
        val newFutureProject = build.userProject.rewriteProject(changeOpts)

        val firstCliCompilation = {
          compiledMacrosState
            .withLogger(cliLogger)
            .compileHandle(
              build.userProject,
              Some(FiniteDuration(2, TimeUnit.SECONDS)),
              beforeTask = Task {
                // Write config file before forcing second compilation
                reloadWithNewProject(newFutureProject, compiledMacrosState).withLogger(cliLogger)
              }
            )
        }

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(10, TimeUnit.SECONDS))
        val firstCliCompiledState =
          Await.result(firstCliCompilation, FiniteDuration(10, TimeUnit.SECONDS))

        assert(firstCompiledState.status == ExitStatus.Ok)
        assert(firstCliCompiledState.status == ExitStatus.Ok)

        //assertValidCompilationState(firstCompiledState, projects)
        assertValidCompilationState(firstCliCompiledState, projects)
        assertDifferentExternalClassesDirs(
          firstCliCompiledState,
          firstCompiledState.toTestState,
          projects
        )

        checkDeduplication(cliLogger, isDeduplicated = false)

        // First compilation should not report warning
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

        // Second compilation should be independent from 1 and report warning
        assertNoDiff(
          cliLogger.warnings.mkString(System.lineSeparator()),
          s"""| [E1] ${TestUtil.universalPath("user/src/main/scala/User2.scala")}:5:19
              |      implicit numeric widening
              |      L5:   val i: Long = 1.toInt
              |                            ^
              |${TestUtil.universalPath("user/src/main/scala/User2.scala")}: L5 [E1]
              |""".stripMargin
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

      val compileStartPromises =
        new mutable.HashMap[scalabsp.BuildTargetIdentifier, Promise[Unit]]()
      val startedFirstCompilation = Promise[Unit]()
      compileStartPromises.put(`B`.bspId, startedFirstCompilation)

      loadBspState(
        workspace,
        projects,
        bspLogger,
        compileStartPromises = Some(compileStartPromises)
      ) { bspState =>
        //val firstDelay = Some(random(400, 100))
        val firstCompilation = bspState.compileHandle(`B`)
        val secondCompilation =
          waitUntilStartAndCompile(compiledState, `B`, startedFirstCompilation, cliLogger1)
        val thirdCompilation =
          waitUntilStartAndCompile(compiledState, `B`, startedFirstCompilation, cliLogger2)

        val firstCompiledState =
          Await.result(firstCompilation, FiniteDuration(5, TimeUnit.SECONDS))
        val (secondCompiledState, thirdCompiledState) =
          TestUtil.blockOnTask(mapBoth(secondCompilation, thirdCompilation), 3)

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
          s"""
             |[E1] ${TestUtil.universalPath("b/src/B.scala")}:3:29
             |     type mismatch;
             |      found   : Int
             |      required: String
             |     L3:   def foo(i: Int): String = i
             |                                     ^
             |${TestUtil.universalPath("b/src/B.scala")}: L3 [E1]
             |Failed to compile 'b'""".stripMargin
        )

        assertNoDiff(
          cliLogger2.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        val targetB = TestUtil.universalPath("b/src/B.scala")
        assertNoDiff(
          cliLogger2.errors.mkString(System.lineSeparator()),
          s"""
             |[E1] ${targetB}:3:29
             |     type mismatch;
             |      found   : Int
             |      required: String
             |     L3:   def foo(i: Int): String = i
             |                                     ^
             |${targetB}: L3 [E1]
             |Failed to compile 'b'""".stripMargin
        )

        /* Repeat the same but this time the CLI client runs the compilation first */

        val startedSecondCompilation = Promise[Unit]()
        compileStartPromises.put(`B`.bspId, startedSecondCompilation)

        val cliLogger4 = new RecordingLogger(ansiCodesSupported = false)
        val cliLogger5 = new RecordingLogger(ansiCodesSupported = false)

        val fourthCompilation = bspState.compileHandle(`B`)
        val fifthCompilation =
          waitUntilStartAndCompile(thirdCompiledState, `B`, startedSecondCompilation, cliLogger4)
        val sixthCompilation =
          waitUntilStartAndCompile(thirdCompiledState, `B`, startedSecondCompilation, cliLogger5)

        val secondBspState =
          Await.result(fourthCompilation, FiniteDuration(4, TimeUnit.SECONDS))
        val (fourthCompiledState, fifthCompiledState) =
          TestUtil.blockOnTask(mapBoth(fifthCompilation, sixthCompilation), 2)

        checkDeduplication(bspLogger, isDeduplicated = false)
        checkDeduplication(cliLogger4, isDeduplicated = true)
        checkDeduplication(cliLogger5, isDeduplicated = true)

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

        assertNoDiff(
          cliLogger4.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          cliLogger4.errors.mkString(System.lineSeparator()),
          s"""
             |[E1] ${targetB}:3:29
             |     type mismatch;
             |      found   : Int
             |      required: String
             |     L3:   def foo(i: Int): String = i
             |                                     ^
             |${targetB}: L3 [E1]
             |Failed to compile 'b'""".stripMargin
        )

        assertNoDiff(
          cliLogger5.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assertNoDiff(
          cliLogger5.errors.mkString(System.lineSeparator()),
          s"""
             |[E1] ${targetB}:3:29
             |     type mismatch;
             |      found   : Int
             |      required: String
             |     L3:   def foo(i: Int): String = i
             |                                     ^
             |${targetB}: L3 [E1]
             |Failed to compile 'b'""".stripMargin
        )

        writeFile(`B`.srcFor("B.scala"), Sources.`B3.scala`)

        val cliLogger7 = new RecordingLogger(ansiCodesSupported = false)

        val startedThirdCompilation = Promise[Unit]()
        compileStartPromises.put(`B`.bspId, startedThirdCompilation)

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

        val seventhCompilation = secondBspState.compileHandle(`B`)
        val eigthCompilation =
          waitUntilStartAndCompile(newCliCompiledState, `B`, startedThirdCompilation, cliLogger7)

        val thirdBspState = Await.result(seventhCompilation, FiniteDuration(3, TimeUnit.SECONDS))
        val seventhCompiledState =
          Await.result(eigthCompilation, FiniteDuration(1, TimeUnit.SECONDS))

        checkDeduplication(bspLogger, isDeduplicated = false)
        checkDeduplication(cliLogger7, isDeduplicated = true)

        assertNoDiff(
          cliLogger7.compilingInfos.mkString(System.lineSeparator()),
          s"""
             |Compiling b (1 Scala source)
         """.stripMargin
        )

        assert(cliLogger7.errors.size == 0)

        assertNoDiff(
          thirdBspState.lastDiagnostics(`B`),
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

  // TODO(jvican): Compile project of cancelled compilation to ensure no deduplication trace is left
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

        // Sleep in independent files to force compiler to check for cancelled status
        val `B2.scala` =
          """/B.scala
            |object B {
            |  def foo(s: String): String = s.toString
            |  macros.SleepMacro.sleep()
            |}
          """.stripMargin

        val `Extra.scala` =
          """/Extra.scala
            |object Extra { def foo(s: String): String = s.toString; macros.SleepMacro.sleep() }
          """.stripMargin

        val `Extra2.scala` =
          """/Extra2.scala
            |object Extra2 { def foo(s: String): String = s.toString; macros.SleepMacro.sleep() }
          """.stripMargin

        val `Extra3.scala` =
          """/Extra3.scala
            |object Extra3 { def foo(s: String): String = s.toString; macros.SleepMacro.sleep() }
          """.stripMargin

        val `Extra4.scala` =
          """/Extra4.scala
            |object Extra4 { def foo(s: String): String = s.toString; macros.SleepMacro.sleep() }
          """.stripMargin

        val `Extra5.scala` =
          """/Extra5.scala
            |object Extra5 { def foo(s: String): String = s.toString; macros.SleepMacro.sleep() }
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
      writeFile(`B`.srcFor("Extra.scala", exists = false), Sources.`Extra.scala`)
      writeFile(`B`.srcFor("Extra2.scala", exists = false), Sources.`Extra2.scala`)
      writeFile(`B`.srcFor("Extra3.scala", exists = false), Sources.`Extra3.scala`)
      writeFile(`B`.srcFor("Extra4.scala", exists = false), Sources.`Extra4.scala`)
      writeFile(`B`.srcFor("Extra5.scala", exists = false), Sources.`Extra5.scala`)

      val compileStartPromises =
        new mutable.HashMap[scalabsp.BuildTargetIdentifier, Promise[Unit]]()
      val startedFirstCompilation = Promise[Unit]()
      compileStartPromises.put(`B`.bspId, startedFirstCompilation)

      loadBspState(
        workspace,
        projects,
        bspLogger,
        compileStartPromises = Some(compileStartPromises)
      ) { bspState =>
        val firstCompilation = bspState.compileHandle(`B`)
        val secondCompilation =
          waitUntilStartAndCompile(compiledState, `B`, startedFirstCompilation, cliLogger)

        val _ = Task
          .fromFuture(startedFirstCompilation.future)
          .map(_ => { firstCompilation.cancel() })
          .runAsync(ExecutionContext.ioScheduler)

        val (firstCompiledState, secondCompiledState) =
          TestUtil.blockOnTask(mapBoth(firstCompilation, secondCompilation), 7, Some(logger))

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
             |  -> Msg: Compiling b (6 Scala sources)
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

  test("cancel deduplication on blocked compilation") {
    // Change default value to speed up test and only wait for 2 seconds
    val testWaitingSeconds = 2
    System.setProperty(
      SystemProperties.Keys.SecondsBeforeDisconnectionKey,
      testWaitingSeconds.toString
    )

    try {
      val logger = new RecordingLogger(ansiCodesSupported = false)
      TestUtil.withinWorkspace { workspace =>
        object Sources {
          val `A.scala` =
            s"""/A.scala
               |package macros
               |
               |import scala.reflect.macros.blackbox.Context
               |import scala.language.experimental.macros
               |
               |object SleepMacro {
               |  def sleep(): Unit = macro sleepImpl
               |  def sleepImpl(c: Context)(): c.Expr[Unit] = {
               |    import c.universe._
               |    Thread.sleep(1000 + ($testWaitingSeconds * 1000))
               |    reify { () }
               |  }
               |}""".stripMargin

          val `B.scala` =
            """/B.scala
              |object B {
              |  def foo(s: String): String = s.toString
              |}
          """.stripMargin

          // Sleep in independent files to force compiler to check for cancelled status
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

        val compileStartPromises =
          new mutable.HashMap[scalabsp.BuildTargetIdentifier, Promise[Unit]]()
        val startedFirstCompilation = Promise[Unit]()
        compileStartPromises.put(`B`.bspId, startedFirstCompilation)

        loadBspState(
          workspace,
          projects,
          bspLogger,
          compileStartPromises = Some(compileStartPromises)
        ) { bspState =>
          val firstCompilation = bspState.compileHandle(`B`)
          val secondCompilation =
            waitUntilStartAndCompile(compiledState, `B`, startedFirstCompilation, cliLogger)

          val (firstCompiledState, secondCompiledState) =
            TestUtil.blockOnTask(mapBoth(firstCompilation, secondCompilation), 10)

          assert(firstCompiledState.status == ExitStatus.CompilationError)
          assertCancelledCompilation(firstCompiledState.toTestState, List(`B`))
          assertNoDiff(
            bspLogger.infos.filterNot(_.contains("tcp")).mkString(System.lineSeparator()),
            """
              |request received: build/initialize
              |BSP initialization handshake complete.
          """.stripMargin
          )

          assert(secondCompiledState.status == ExitStatus.Ok)
          assertValidCompilationState(secondCompiledState, List(`B`))

          checkDeduplication(bspLogger, isDeduplicated = false)
          // Deduplication happens even if there is a disconnection afterwards
          checkDeduplication(cliLogger, isDeduplicated = true)

          assertNoDiff(
            cliLogger.warnings.mkString(System.lineSeparator()),
            """Disconnecting from deduplication of ongoing compilation for 'b'
              |No progress update for 2 seconds caused bloop to cancel compilation and schedule a new compile.
              |""".stripMargin
          )

          // Skip deduplication log that can happen because of timing issues, we're only interested in deduplicating b
          val cliLogs = cliLogger.renderTimeInsensitiveInfos
            .split(System.lineSeparator)
            .filter(!_.startsWith("Deduplicating compilation of a"))
            .mkString(System.lineSeparator)

          assertNoDiff(
            cliLogs,
            """|Compiling a (1 Scala source)
               |Compiled a ???
               |Compiling b (1 Scala source)
               |Compiled b ???
               |Deduplicating compilation of b from bsp client 'test-bloop-client 1.0.0' (since ???
               |Compiling b (1 Scala source)
               |Compiling b (1 Scala source)
               |Compiled b ???
               |""".stripMargin
          )
        }
      }
    } finally {
      // Restore the previous value before running this test
      System.setProperty(
        SystemProperties.Keys.SecondsBeforeDisconnectionKey,
        SystemProperties.Defaults.SecondsBeforeDisconnection.toString
      )
      ()
    }
  }
}
