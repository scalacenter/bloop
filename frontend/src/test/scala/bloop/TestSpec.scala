package bloop

import java.util.concurrent.TimeUnit

import scala.concurrent.Await
import scala.concurrent.duration.Duration

import bloop.cli.ExitStatus
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.logging.RecordingLogger
import bloop.testing.LoggingEventHandler
import bloop.testing.ProjectBaseSuite
import bloop.util.TestProject
import bloop.util.TestUtil

abstract class BaseTestSpec(val projectName: String, buildName: String)
    extends ProjectBaseSuite(buildName) {
  val testOnlyOnJava8: Boolean = buildName == "cross-test-build-scalajs-0.6"
  testProject("project compiles", testOnlyOnJava8) { (build, _) =>
    val project = build.projectFor(projectName)
    val compiledState = build.state.compile(project)
    assert(compiledState.status == ExitStatus.Ok)
  }

  val expectedFullTestsOutput: String
  testProject("runs all available suites", testOnlyOnJava8) { (build, logger) =>
    val project = build.projectFor(projectName)
    build.state.test(project)
    try assert(logger.errors.size == 0)
    catch { case _: AssertionError => logger.dump() }
    assertNoDiff(
      logger.renderTimeInsensitiveTestInfos,
      expectedFullTestsOutput
    )
  }

  val terminalWidth = LoggingEventHandler.getTerminalWidth
  val delimiter = "=" * terminalWidth
}

object SeedTestSpec extends BaseTestSpec("root-test", "scala-seed-project") {
  val expectedFullTestsOutput: String =
    s"""|HelloSpec:
        |The Hello object
        |- should say hello
        |Execution took ???
        |1 tests, 1 passed
        |All tests in example.HelloSpec passed
        |
        |$delimiter
        |Total duration: ???
        |All 1 test suites passed.
        |$delimiter
        |""".stripMargin
}

object ResourcesTestSpec extends BaseTestSpec("root-test", "resources-test-project") {
  val expectedFullTestsOutput: String =
    s"""|[32mResourcesTest:[0m
        |[32m + [0m[32mresources[0m ???
        |Execution took ???
        |1 tests, 1 passed
        |All tests in ResourcesTest passed
        |
        |$delimiter
        |Total duration: ???
        |All 1 test suites passed.
        |$delimiter
        |""".stripMargin
}

object JsTestSpec extends BaseTestSpec("test-projectJS-test", "cross-test-build-scalajs-0.6") {
  val expectedFullTestsOutput: String = {
    s"""|Execution took ???
        |1 tests, 1 passed
        |All tests in hello.JUnitTest passed
        |
        |+ Greeting.is personal: OK, passed 100 tests.
        |Summary: Passed: Total 1, Failed 0, Errors 0, Passed 1 Warning: Unknown ScalaCheck args provided: -o
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.ScalaCheckTest passed
        |
        |ScalaTestTest:
        |A greeting
        |- should be very personal
        |Summary: Run completed in ??? Total number of tests run: 0 Suites: completed 0, aborted 0 Tests: succeeded 0, failed 0, canceled 0, ignored 0, pending 0 No tests were executed.
        |Execution took ???
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
        |Finished in ??? 1 example, 0 failure, 0 error
        |
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.Specs2Test passed
        |
        |Summary: Tests: 1, Passed: 1, Failed: 0
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.UTestTest passed
        |
        |$delimiter
        |Total duration: ???
        |All 5 test suites passed.
        |$delimiter
        |""".stripMargin

  }
}

object JvmTestSpec extends BaseTestSpec("test-project-test", "cross-test-build-scalajs-0.6") {
  val expectedFullTestsOutput: String = {
    s"""|Test run started
        |Test hello.JUnitTest.myTest started
        |Test run finished: 0 failed, 0 ignored, 1 total, ???
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.JUnitTest passed
        |
        |Execution took ???
        |No test suite was run
        |
        |+ Greeting.is personal: OK, passed 100 tests.
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.ScalaCheckTest passed
        |
        |ResourcesTest:
        |Resources
        |- should be found
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.ResourcesTest passed
        |
        |ScalaTestTest:
        |A greeting
        |- should be very personal
        |Execution took ???
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
        |Finished in ??? 1 example, 0 failure, 0 error
        |
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.Specs2Test passed
        |
        |-------------------------------- Running Tests --------------------------------
        |+ hello.UTestTest.Greetings are very personal ???
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.UTestTest passed
        |
        |$delimiter
        |Total duration: ???
        |6 passed
        |$delimiter""".stripMargin
  }

  testProject("test options work when one framework is singled out", runOnlyOnJava8 = true) {
    (build, logger) =>
      val project = build.projectFor(projectName)
      build.state.test(project, List("hello.JUnitTest"), List("*myTest*"))
      assertNoDiff(
        logger.renderTimeInsensitiveTestInfos,
        """Test run started
          |Test hello.JUnitTest.myTest started
          |Test run finished: 0 failed, 0 ignored, 1 total, ???
          |Execution took ???
          |1 tests, 1 passed
          |All tests in hello.JUnitTest passed
          |
          |$delimiter
          |Total duration: ???
          |All 1 test suites passed.
          |$delimiter""".stripMargin
      )
  }

  testProject("test exclusions work", runOnlyOnJava8 = true) { (build, logger) =>
    val project = build.projectFor(projectName)
    build.state.test(project, List("-hello.JUnitTest"), Nil)
    assertNoDiff(
      logger.renderTimeInsensitiveTestInfos,
      s"""|Execution took ???
          |No test suite was run
          |
          |+ Greeting.is personal: OK, passed 100 tests.
          |Execution took ???
          |1 tests, 1 passed
          |All tests in hello.ScalaCheckTest passed
          |
          |ResourcesTest:
          |Resources
          |- should be found
          |Execution took ???
          |1 tests, 1 passed
          |All tests in hello.ResourcesTest passed
          |
          |ScalaTestTest:
          |A greeting
          |- should be very personal
          |Execution took ???
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
          |Finished in ??? 1 example, 0 failure, 0 error
          |
          |Execution took ???
          |1 tests, 1 passed
          |All tests in hello.Specs2Test passed
          |
          |-------------------------------- Running Tests --------------------------------
          |+ hello.UTestTest.Greetings are very personal ???
          |Execution took ???
          |1 tests, 1 passed
          |All tests in hello.UTestTest passed
          |
          |$delimiter
          |Total duration: ???
          |5 passed
          |$delimiter""".stripMargin
    )
  }

  testProject("specifying -h in Scalatest runner works", runOnlyOnJava8 = true) { (build, logger) =>
    val project = build.projectFor(projectName)
    val scalatestArgs = List("-h", "target/test-reports")
    build.state.test(project, List("hello.ScalaTestTest"), scalatestArgs)
    assertNoDiff(
      logger.renderTimeInsensitiveTestInfos,
      """ScalaTestTest:
        |A greeting
        |- should be very personal
        |Execution took ???
        |1 tests, 1 passed
        |All tests in hello.ScalaTestTest passed
        |
        |$delimiter
        |Total duration: ???
        |All 1 test suites passed.
        |$delimiter""".stripMargin
    )
  }

  testProject("test options don't work when no framework is singled out", runOnlyOnJava8 = true) {
    (build, logger) =>
      val project = build.projectFor(projectName)
      build.state.test(project, Nil, List("*myTest*"))

      assertNoDiff(
        logger.warnings.mkString(lineSeparator),
        "Ignored CLI test options 'List(*myTest*)' can only be applied to one framework, found: JUnit, ScalaCheck, ScalaTest, specs2, utest"
      )

      assertNoDiff(
        logger.renderTimeInsensitiveTestInfos,
        s"""|Test run started
            |Test hello.JUnitTest.myTest started
            |Test run finished: 0 failed, 0 ignored, 1 total, ???
            |Execution took ???
            |1 tests, 1 passed
            |All tests in hello.JUnitTest passed
            |
            |Execution took ???
            |No test suite was run
            |
            |+ Greeting.is personal: OK, passed 100 tests.
            |Execution took ???
            |1 tests, 1 passed
            |All tests in hello.ScalaCheckTest passed
            |
            |ResourcesTest:
            |Resources
            |- should be found
            |Execution took ???
            |1 tests, 1 passed
            |All tests in hello.ResourcesTest passed
            |
            |ScalaTestTest:
            |A greeting
            |- should be very personal
            |Execution took ???
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
            |Finished in ??? 1 example, 0 failure, 0 error
            |
            |Execution took ???
            |1 tests, 1 passed
            |All tests in hello.Specs2Test passed
            |
            |-------------------------------- Running Tests --------------------------------
            |+ hello.UTestTest.Greetings are very personal ???
            |Execution took ???
            |1 tests, 1 passed
            |All tests in hello.UTestTest passed
            |
            |$delimiter
            |Total duration: ???
            |6 passed
            |$delimiter""".stripMargin
      )
  }

  testProject("cancel test execution works", runOnlyOnJava8 = true) { (build, _) =>
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
          |    Thread.sleep(1000)
          |    assertEquals(4, 2 + 2)
          |    Thread.sleep(1000)
          |    assertEquals(4, 2 + 2)
          |    Thread.sleep(1000)
          |    assertEquals(4, 2 + 2)
          |    Thread.sleep(1000)
          |    assertEquals(4, 2 + 2)
          |    Thread.sleep(1000)
          |    assertEquals(4, 2 + 2)
          |    Thread.sleep(1000)
          |    assertEquals(4, 2 + 2)
          |  }
          |}""".stripMargin
    }

    val testProject = build.projectFor(projectName)
    val junitTestSrc = testProject.srcFor("hello/JUnitTest.scala")
    val oldContents = readFile(junitTestSrc)
    writeFile(junitTestSrc, Sources.`JUnitTest.scala`)
    try {
      val compiledState = build.state.compile(testProject)
      assert(compiledState.status == ExitStatus.Ok)

      val futureTestState = compiledState.testHandle(
        testProject,
        List("hello.JUnitTest"),
        Nil,
        None,
        userScheduler = Some(ExecutionContext.ioScheduler)
      )

      ExecutionContext.ioScheduler.scheduleOnce(
        3000,
        TimeUnit.MILLISECONDS,
        new Runnable { override def run(): Unit = futureTestState.cancel() }
      )

      val testState = {
        try Await.result(futureTestState, Duration(7000, "ms"))
        catch {
          case scala.util.control.NonFatal(t) => futureTestState.cancel(); throw t
          case _: InterruptedException =>
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

object NoTestFrameworksSpec extends ProjectBaseSuite("no-test-frameworks") {
  testProjectTask("must have frameworks in test project", runOnlyOnJava8 = false) {
    (build, logger) =>
      val project = build.projectFor("myProject")
      build.state.testTask(project).map { testState =>
        try {
          assert(!testState.status.isOk)
          assert(logger.errors.contains("Missing configured test frameworks in myProject-test"))
        } catch { case err: AssertionError => logger.dump(); throw err }
      }
  }

  testProjectTask("non-test projects can have empty frameworks", runOnlyOnJava8 = false) {
    (rawBuild, logger) =>
      val build = rawBuild.filterProjectsByName(!_.endsWith("-test"))
      val project = build.projectFor("myProject")
      build.state.testTask(project).map { testState =>
        try {
          assert(testState.status.isOk)
          // No message is logged - this is not a test target, and therefore it is ignored.
        } catch { case err: AssertionError => logger.dump(); throw err }
      }
  }
}

object TestResourcesSpec extends bloop.testing.BaseSuite {
  test("test sees runtime resources") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `a/A.scala` =
          """/a/A.scala
            |class A {
            |  @org.junit.Test
            |  def myTest(): Unit = {
            |    val res = getClass.getClassLoader.getResourceAsStream("resource.txt")
            |    val content = scala.io.Source.fromInputStream(res).mkString
            |    org.junit.Assert.assertEquals("goodbye", content)
            |  }
            |}""".stripMargin
      }
      object Resources {
        val `a/compile-resources/resource.txt` =
          """/resource.txt
            |hello""".stripMargin
        val `a/run-resources/resource.txt` =
          """/resource.txt
            |goodbye""".stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`a/A.scala`),
        enableTests = true,
        jars = bloop.internal.build.BuildTestInfo.junitTestJars.map(AbsolutePath.apply).toArray,
        resources = List(Resources.`a/compile-resources/resource.txt`),
        runtimeResources = Some(List(Resources.`a/run-resources/resource.txt`))
      )
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val runState = state.test(`A`)
      assertEquals(ExitStatus.Ok, runState.status)
    }
  }
}

object TestCompileErrorExitCode extends bloop.testing.BaseSuite {
  test("exit code reflects compilation errors in tests") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `a/A.scala` =
          """/a/A.scala
            |invalid source file""".stripMargin
      }
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`a/A.scala`))
      val projects = List(`A`)
      val state = loadState(workspace, projects, logger)
      val testState = state.test(`A`)
      assertEquals(ExitStatus.CompilationError, testState.status)
    }
  }
}

object MultiFingerprintMatch extends BaseTestSpec("test-test", "custom-test-framework") {
  val expectedFullTestsOutput: String =
    s"""|Running task: foo.MyTest
        |Execution took ???
        |No test suite was run
        |
        |$delimiter
        |Total duration: ???
        |
        |$delimiter
        |""".stripMargin
}
