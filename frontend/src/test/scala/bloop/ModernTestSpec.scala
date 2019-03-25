package bloop

import bloop.config.Config
import bloop.io.{AbsolutePath, RelativePath, Paths => BloopPaths}
import bloop.logging.RecordingLogger
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Feedback, Run, State, ExecutionContext}
import bloop.engine.caches.ResultsCache
import bloop.util.{TestProject, TestUtil, BuildUtil}
import bloop.testing.ProjectBaseSuite

import java.nio.file.Files
import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.CancelableFuture

object ModernTestSpec extends ProjectBaseSuite("cross-test-build-0.6") {
  testProject("project compiles") { (build, logger) =>
    val project = build.projectFor("test-project-test")
    val compiledState = build.state.compile(project)
    assert(compiledState.status == ExitStatus.Ok)
  }

  testProject("runs all available suites") { (build, logger) =>
    val project = build.projectFor("test-project-test")
    val testState = build.state.test(project)
    assertNoDiff(
      logger.renderTimeInsensitiveTestInfos,
      """|Test run started
         |Test hello.JUnitTest.myTest started
         |Test run finished: 0 failed, 0 ignored, 1 total, ???s
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.JUnitTest passed
         |
         |Execution took ???ms
         |No test suite was run
         |
         |+ Greeting.is personal: OK, passed 100 tests.
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.ScalaCheckTest passed
         |
         |ResourcesTest:
         |Resources
         |- should be found
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.ResourcesTest passed
         |
         |ScalaTestTest:
         |A greeting
         |- should be very personal
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.ScalaTestTest passed
         |
         |Specs2Test
         |
         | This is a specification to check the `Hello` object.
         |
         | A greeting
         | + is very personal
         |
         |Total for specification Specs2Test
         |Finished in ??? ms 1 example, 0 failure, 0 error
         |
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.Specs2Test passed
         |
         |-------------------------------- Running Tests --------------------------------
         |+ hello.UTestTest.Greetings are very personal ???ms
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.UTestTest passed
         |
         |===============================================
         |Total duration: ???ms
         |6 passed
         |===============================================""".stripMargin
    )
  }

  testProject("test options work when one framework is singled out") { (build, logger) =>
    val project = build.projectFor("test-project-test")
    val testState = build.state.test(project, List("hello.JUnitTest"), List("*myTest*"))
    assertNoDiff(
      logger.renderTimeInsensitiveTestInfos,
      """Test run started
        |Test hello.JUnitTest.myTest started
        |Test run finished: 0 failed, 0 ignored, 1 total, ???s
        |Execution took ???ms
        |1 tests, 1 passed
        |All tests in hello.JUnitTest passed
        |
        |===============================================
        |Total duration: ???ms
        |All 1 test suites passed.
        |===============================================""".stripMargin
    )
  }

  testProject("specifying -h in Scalatest runner works") { (build, logger) =>
    val project = build.projectFor("test-project-test")
    val scalatestArgs = List("-h", "target/test-reports")
    val testState = build.state.test(project, List("hello.ScalaTestTest"), scalatestArgs)
    assertNoDiff(
      logger.renderTimeInsensitiveTestInfos,
      """ScalaTestTest:
        |A greeting
        |- should be very personal
        |Execution took ???ms
        |1 tests, 1 passed
        |All tests in hello.ScalaTestTest passed
        |
        |===============================================
        |Total duration: ???ms
        |All 1 test suites passed.
        |===============================================""".stripMargin
    )
  }

  testProject("test options don't work when none framework is singled out") { (build, logger) =>
    val project = build.projectFor("test-project-test")
    val testState = build.state.test(project, Nil, List("*myTest*"))

    assertNoDiff(
      logger.warnings.mkString(System.lineSeparator()),
      "Ignored CLI test options 'List(*myTest*)' can only be applied to one framework, found: JUnit, ScalaCheck, ScalaTest, specs2, utest"
    )

    assertNoDiff(
      logger.renderTimeInsensitiveTestInfos,
      """|Test run started
         |Test hello.JUnitTest.myTest started
         |Test run finished: 0 failed, 0 ignored, 1 total, ???s
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.JUnitTest passed
         |
         |Execution took ???ms
         |No test suite was run
         |
         |+ Greeting.is personal: OK, passed 100 tests.
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.ScalaCheckTest passed
         |
         |ResourcesTest:
         |Resources
         |- should be found
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.ResourcesTest passed
         |
         |ScalaTestTest:
         |A greeting
         |- should be very personal
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.ScalaTestTest passed
         |
         |Specs2Test
         |
         | This is a specification to check the `Hello` object.
         |
         | A greeting
         | + is very personal
         |
         |Total for specification Specs2Test
         |Finished in ??? ms 1 example, 0 failure, 0 error
         |
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.Specs2Test passed
         |
         |-------------------------------- Running Tests --------------------------------
         |+ hello.UTestTest.Greetings are very personal ???ms
         |Execution took ???ms
         |1 tests, 1 passed
         |All tests in hello.UTestTest passed
         |
         |===============================================
         |Total duration: ???ms
         |6 passed
         |===============================================""".stripMargin
    )
  }

  testProject("cancel test execution works") { (build, logger) =>
    object Sources {
      val `JUnitTest.scala` =
        """package hello
          |
          |import org.junit.Test
          |import org.junit.Assert.assertEquals
          |
          |class JUnitTest {
          |  @Test
          |  def myTest: Unit = {
          |    Thread.sleep(1500)
          |    assertEquals(4, 2 + 2)
          |  }
          |}""".stripMargin
    }

    val testProject = build.projectFor("test-project-test")
    val junitTestSrc = testProject.srcFor("hello/JUnitTest.scala")
    val oldContents = readFile(junitTestSrc)
    writeFile(junitTestSrc, Sources.`JUnitTest.scala`)
    try {
      val compiledState = build.state.compile(testProject)
      assert(compiledState.status == ExitStatus.Ok)
      val futureTestState =
        compiledState.testHandle(testProject, List("hello.JUnitTest"), Nil, None)

      val waitTimeToCancel = {
        val randomMs = scala.util.Random.nextInt(750)
        (250 + randomMs).toLong
      }

      ExecutionContext.ioScheduler.scheduleOnce(
        waitTimeToCancel,
        TimeUnit.MILLISECONDS,
        new Runnable { override def run(): Unit = futureTestState.cancel() }
      )

      val testState = {
        try Await.result(futureTestState, Duration(1100, "ms"))
        catch {
          case scala.util.control.NonFatal(t) => futureTestState.cancel(); throw t
          case i: InterruptedException =>
            futureTestState.cancel()
            sys.error("Test execution didn't finish!")
        }
      }

      assert(testState.status == ExitStatus.TestExecutionError)
    } finally {
      // Undo changes so that next test doesn't run the slow test
      writeFile(junitTestSrc, oldContents)
      build.state.compile(testProject)
      ()
    }
  }
}
