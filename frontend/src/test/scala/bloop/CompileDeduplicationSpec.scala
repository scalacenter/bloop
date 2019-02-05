package bloop

import bloop.cli.{Commands, ExitStatus, BspProtocol, CliOptions}
import bloop.logging.{RecordingLogger, DebugFilter}
import bloop.util.{TestUtil, BuildUtil}
import bloop.engine.{Run, State}
import bloop.engine.tasks.Tasks
import bloop.io.AbsolutePath
import bloop.bsp.BspClientTest
import bloop.bsp.BspClientTest.BspClientAction
import bloop.engine.ExecutionContext
import bloop.engine.caches.ResultsCache

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.experimental.categories.Category
import org.junit.{Assert, Test}

import ch.epfl.scala.bsp.BuildTargetIdentifier
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Await
import monix.eval.Task

@Category(Array(classOf[bloop.FastTests]))
class CompileDeduplicationSpec {
  @Test
  def deduplicateCompilationViaCli(): Unit = {
    val logger = new RecordingLogger
    BuildUtil.testSlowBuild(logger) { build =>
      val state = build.state.copy(results = ResultsCache.emptyForTests)
      val compileMacroProject = Run(Commands.Compile(List("macros")))
      val compiledMacrosState = TestUtil.blockingExecute(compileMacroProject, state)
      Assert.assertTrue(
        "Unexpected compilation error when compiling macros",
        compiledMacrosState.status.isOk
      )

      val logger1 = new RecordingLogger
      val logger2 = new RecordingLogger
      val compileUserProject = Run(Commands.Compile(List("user")))
      val firstCompilation = TestUtil
        .interpreterTask(compileUserProject, compiledMacrosState.copy(logger = logger1))
        .runAsync(ExecutionContext.scheduler)
      val secondCompilation = TestUtil
        .interpreterTask(compileUserProject, compiledMacrosState.copy(logger = logger2))
        .delayExecution(FiniteDuration(2, TimeUnit.SECONDS))
        .runAsync(ExecutionContext.scheduler)

      val firstCompilationState =
        Await.result(firstCompilation, FiniteDuration(10, TimeUnit.SECONDS))
      // We make sure that the second compilation returns just after the first + ± delay
      val secondCompilationState =
        Await.result(secondCompilation, FiniteDuration(500, TimeUnit.MILLISECONDS))

      TestUtil.assertNoDiff(
        s"""
           |Compiling macros (1 Scala source)
         """.stripMargin,
        logger.compilingInfos.mkString(System.lineSeparator())
      )

      TestUtil.assertNoDiff(
        s"""
           |Compiling user (1 Scala source)
         """.stripMargin,
        logger1.compilingInfos.mkString(System.lineSeparator())
      )

      TestUtil.assertNoDiff(
        s"""
           |Compiling user (1 Scala source)
         """.stripMargin,
        logger2.compilingInfos.mkString(System.lineSeparator())
      )

      val cleanAndCompile = Run(Commands.Clean(List("user")), Run(Commands.Compile(List("user"))))
      val stateAfterFreshCompile =
        TestUtil.blockOnTask(TestUtil.interpreterTask(cleanAndCompile, secondCompilationState), 10)

      // Expect bloop to recompile user (e.g. compilation is removed from ongoing compilations map)
      TestUtil.assertNoDiff(
        s"""
           |Compiling user (1 Scala source)
           |Compiling user (1 Scala source)
         """.stripMargin,
        logger2.compilingInfos.mkString(System.lineSeparator())
      )

      val noopCompilation =
        TestUtil.interpreterTask(Run(Commands.Compile(List("user"))), stateAfterFreshCompile)
      val noopCompiles = noopCompilation.zip(noopCompilation)
      val _ = TestUtil.blockOnTask(noopCompiles, 10)

      // Expect noop compilations (no change in the compilation output wrt previous iteration)
      TestUtil.assertNoDiff(
        s"""
           |Compiling user (1 Scala source)
           |Compiling user (1 Scala source)
         """.stripMargin,
        logger2.compilingInfos.mkString(System.lineSeparator())
      )
    }
  }

  @Test
  def deduplicateCompilationInMultipleBspAndCliClients(): Unit = {
    val logger = new RecordingLogger
    BuildUtil.testSlowBuild(logger) { build =>
      val compileMacroProject = Run(Commands.Compile(List("macros")))
      val compiledMacrosState = TestUtil.blockingExecute(compileMacroProject, build.state)
      Assert.assertTrue(
        "Unexpected compilation error when compiling macros",
        compiledMacrosState.status.isOk
      )

      /* Set up four clients: two BSP clients and two CLI clients. One of the BSP
       * clients is the one that gets to trigger the compilation in the first place
       * and the rest of the clients reuse it and replay all events generated during
       * the compilation. Afterwards, we test all of them see the same thing. */

      val cliLogger1 = new RecordingLogger(debugFilter = DebugFilter.Compilation)
      val cliLogger2 = new RecordingLogger(debugFilter = DebugFilter.Compilation)
      val bspLogger1 = new RecordingLogger(debugFilter = DebugFilter.Compilation)
      val bspLogger2 = new RecordingLogger(debugFilter = DebugFilter.Compilation)
      val compileUserProject = Run(Commands.Compile(List("user")))

      // Spawn first CLI client in two seconds
      val cliCompilation1 = TestUtil
        .interpreterTask(compileUserProject, compiledMacrosState.copy(logger = cliLogger1))
        .delayExecution(FiniteDuration(2, TimeUnit.SECONDS))
        .runAsync(ExecutionContext.scheduler)

      // Spawn second CLI client in two seconds
      val cliCompilation2 = TestUtil
        .interpreterTask(compileUserProject, compiledMacrosState.copy(logger = cliLogger2))
        .delayExecution(FiniteDuration(2, TimeUnit.SECONDS))
        .runAsync(ExecutionContext.scheduler)

      val configDir = compiledMacrosState.build.origin
      val actions = List(BspClientAction.Compile(build.userProject.bspId))

      // Spawn second BSP client in two seconds
      val bspCompilationDiagnostics2 = Task {
        val cmd = {
          val bspCmd = Commands.Bsp(protocol = BspProtocol.Tcp, port = 5102)
          BspClientTest.validateBsp(bspCmd, configDir)
        }

        // Use our own scheduler because `runCompileTest` uses a fixed 4 thread pool
        val userScheduler = Some(ExecutionContext.scheduler)
        val userState = Some(compiledMacrosState.copy(logger = bspLogger2))
        BspClientTest.runCompileTest(
          cmd,
          actions,
          configDir,
          userState = userState,
          userScheduler = userScheduler
        )
      }.delayExecution(FiniteDuration(2, TimeUnit.SECONDS)).runAsync(ExecutionContext.scheduler)

      // Proceed to spawn the driver BSP client and block on its result
      val bspDiagnostics1 = {
        val cmd = BspClientTest.createTcpBspCommand(configDir, false)
        val userState = Some(compiledMacrosState.copy(logger = bspLogger1))
        BspClientTest.runCompileTest(cmd, actions, configDir, userState = userState)
      }

      // The cli compilation reuses the same BSP compile request and returns ~immediately
      val cliCompilationState1 =
        Await.result(cliCompilation1, FiniteDuration(600, TimeUnit.MILLISECONDS))
      val cliCompilationState2 =
        Await.result(cliCompilation2, FiniteDuration(50, TimeUnit.MILLISECONDS))
      val bspDiagnostics2 =
        Await.result(bspCompilationDiagnostics2, FiniteDuration(50, TimeUnit.MILLISECONDS))

      // Macros is only compiled by the first BSP compile request
      BspClientTest.checkDiagnostics(bspDiagnostics1)(
        build.macroProject.bspId,
        """#1: task start 1
          |  -> Msg: Start no-op compilation for macros
          |  -> Data kind: compile-task
          |#1: task finish 1
          |  -> errors 0, warnings 0
          |  -> Msg: Compiled 'macros'
          |  -> Data kind: compile-report""".stripMargin
      )

      // The second BSP compile request doesn't receive any notification related to the macros project
      BspClientTest.checkDiagnostics(bspDiagnostics2)(build.macroProject.bspId, "")

      def checkBspDiagnostics(
          expectedId: Int,
          diagnostics: Map[BuildTargetIdentifier, String]
      ): Unit = {
        // The compilation of the user project is successfully triggered
        BspClientTest.checkDiagnostics(diagnostics)(
          build.userProject.bspId,
          s"""#1: task start ${expectedId}
             |  -> Msg: Compiling user (1 Scala source)
             |  -> Data kind: compile-task
             |#1: task finish ${expectedId}
             |  -> errors 0, warnings 0
             |  -> Msg: Compiled 'user'
             |  -> Data kind: compile-report""".stripMargin
        )
      }

      checkBspDiagnostics(2, bspDiagnostics1)
      checkBspDiagnostics(1, bspDiagnostics2)

      // Although BSP request triggered compilation, compilation events are replayed for CLI streams
      TestUtil.assertNoDiff(
        "Compiling user (1 Scala source)",
        cliLogger1.compilingInfos.mkString(System.lineSeparator)
      )

      def stableDebugInfosFrom(logger: RecordingLogger): List[String] =
        logger.getMessagesAt(Some("debug")).filterNot(_.contains("compiler-bridge"))

      val cliCompileDebugMessages = stableDebugInfosFrom(cliLogger1)
      val firstCliDebugMessage = cliCompileDebugMessages.head
      val bspCompileDebugMessages = {
        // Pick only last bsp messages which correspond to the user project and not the macros project
        stableDebugInfosFrom(bspLogger1).dropWhile(msg => msg != firstCliDebugMessage)
      }

      // Assert that debug messages are identical for both clients (replaying log events works)
      assertTrue(cliCompileDebugMessages.size > 0)
      assertTrue(bspCompileDebugMessages.size > 0)
      TestUtil.assertNoDiff(
        cliCompileDebugMessages.mkString(System.lineSeparator),
        bspCompileDebugMessages.mkString(System.lineSeparator)
      )

      // Ensure that CLI #1 and CLI #2 contain the same logs
      TestUtil.assertNoDiff(
        cliLogger1.getMessagesAt(None).mkString(System.lineSeparator),
        cliLogger2.getMessagesAt(None).mkString(System.lineSeparator)
      )
    }
  }
}
