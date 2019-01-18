package bloop

import bloop.cli.{Commands, ExitStatus}
import bloop.logging.RecordingLogger
import bloop.util.{TestUtil, BuildUtil}
import bloop.engine.{Run, State}
import bloop.io.AbsolutePath

import org.junit.Assert.{assertEquals, assertFalse, assertTrue}
import org.junit.experimental.categories.Category
import org.junit.{Assert, Test}

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration

@Category(Array(classOf[bloop.FastTests]))
class CompileDeduplicationSpec {
  @Test
  def deduplicateCompilation(): Unit = {
    import scala.concurrent.Await
    import bloop.engine.ExecutionContext
    val logger = new RecordingLogger
    val scalaJars = TestUtil.scalaInstance.allJars.map(AbsolutePath.apply)
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
  def deduplicateCompilationViaBsp(): Unit = {
    import scala.concurrent.Await
    import bloop.engine.ExecutionContext
    val logger = new RecordingLogger
    val scalaJars = TestUtil.scalaInstance.allJars.map(AbsolutePath.apply)
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
}
