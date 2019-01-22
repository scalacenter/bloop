package bloop

import bloop.cli.{Commands, ExitStatus}
import bloop.logging.{RecordingLogger, DebugFilter}
import bloop.util.{TestUtil, BuildUtil}
import bloop.engine.{Run, State}
import bloop.engine.tasks.Tasks
import bloop.io.AbsolutePath
import bloop.bsp.BspClientTest
import bloop.bsp.BspClientTest.BspClientAction
import bloop.engine.ExecutionContext

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.experimental.categories.Category
import org.junit.{Assert, Test}

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
      import build.state
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
  def deduplicateCompilationViaBspAndCli(): Unit = {
    val logger = new RecordingLogger
    BuildUtil.testSlowBuild(logger) { build =>
      val compileMacroProject = Run(Commands.Compile(List("macros")))
      val compiledMacrosState = TestUtil.blockingExecute(compileMacroProject, build.state)
      // Write the analysis files to the bloop classes dir so that the bsp session loads them
      Tasks.persist(compiledMacrosState, _ => ()).runAsync(ExecutionContext.ioScheduler)
      Assert.assertTrue(
        "Unexpected compilation error when compiling macros",
        compiledMacrosState.status.isOk
      )

      val cliLogger1 = new RecordingLogger(debugFilter = DebugFilter.Compilation)
      val cliLogger2 = new RecordingLogger(debugFilter = DebugFilter.Compilation)
      val bspLogger = new RecordingLogger(debugFilter = DebugFilter.Compilation)
      val compileUserProject = Run(Commands.Compile(List("user")))

      val cliCompilation1 = TestUtil
        .interpreterTask(compileUserProject, compiledMacrosState.copy(logger = cliLogger1))
        .delayExecution(FiniteDuration(2, TimeUnit.SECONDS))
        .runAsync(ExecutionContext.scheduler)

      val cliCompilation2 = TestUtil
        .interpreterTask(compileUserProject, compiledMacrosState.copy(logger = cliLogger2))
        .delayExecution(FiniteDuration(2, TimeUnit.SECONDS))
        .runAsync(ExecutionContext.scheduler)

      val configDir = compiledMacrosState.build.origin
      val cmd = BspClientTest.createTcpBspCommand(configDir, false)
      val actions = List(BspClientAction.Compile(build.userProject.bspId))

      // This is a blocking request, so after it returns the bsp compilation is done
      val bspState = compiledMacrosState.copy(logger = bspLogger)
      val bspDiagnostics = BspClientTest.runCompileTest(
        cmd,
        actions,
        configDir,
        userState = Some(bspState)
      )

      // The cli compilation reuses the same BSP compile request and returns almost immediately
      val cliCompilationState1 =
        Await.result(cliCompilation1, FiniteDuration(500, TimeUnit.MILLISECONDS))
      val cliCompilationState2 =
        Await.result(cliCompilation2, FiniteDuration(100, TimeUnit.MILLISECONDS))

      // As macros is compiled and its analysis file is persisted, `macros` is a no-op
      BspClientTest.checkDiagnostics(bspDiagnostics)(
        build.macroProject.bspId,
        """#1: task start 2
          |  -> Msg: Start no-op compilation for macros
          |  -> Data kind: compile-task
          |#1: task finish 2
          |  -> errors 0, warnings 0
          |  -> Msg: Compiled 'macros'
          |  -> Data kind: compile-report""".stripMargin
      )

      // The compilation of the user project is successfully triggered
      BspClientTest.checkDiagnostics(bspDiagnostics)(
        build.userProject.bspId,
        """#1: task start 1
          |  -> Msg: Compiling user (1 Scala source)
          |  -> Data kind: compile-task
          |#1: task finish 1
          |  -> errors 0, warnings 0
          |  -> Msg: Compiled 'user'
          |  -> Data kind: compile-report""".stripMargin
      )

      // Although BSP request triggered compilation, compilation events are replayed for CLI streams
      TestUtil.assertNoDiff(
        "Compiling user (1 Scala source)",
        cliLogger1.compilingInfos.mkString(System.lineSeparator)
      )

      val cliCompileDebugMessages = cliLogger1.getMessagesAt(Some("debug"))
      val firstCliDebugMessage = cliCompileDebugMessages.head
      // Pick only last bsp messages which correspond to the user project and not the macros project
      val bspCompileDebugMessages = {
        bspLogger.getMessagesAt(Some("debug")).dropWhile(msg => msg != firstCliDebugMessage)
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
