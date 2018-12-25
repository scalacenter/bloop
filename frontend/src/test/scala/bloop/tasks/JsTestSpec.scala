package bloop.tasks

import java.net.URLClassLoader
import java.util
import java.util.concurrent.{ExecutionException, TimeUnit}

import bloop.CompileMode
import bloop.cli.Commands
import bloop.data.Project
import bloop.engine.tasks.{CompilationTask, Tasks, TestTask}
import bloop.engine.{ExecutionContext, Run, State}
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.reporter.ReporterConfig
import bloop.testing.{LoggingEventHandler, TestInternals, TestSuiteEvent}
import monix.execution.Cancelable
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import org.junit.{Assert, Test}
import sbt.testing.Framework
import xsbti.compile.CompileAnalysis

import scala.collection.mutable.ListBuffer
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.control.NonFatal

// The execution of this test suite requires the local publication of `jsBridge06` and `jsBridge10`
object JsTestSpec {
  // Test that frameworks are class-loaded, detected and that test classes exist and can be run.
  val frameworks06 = Array("ScalaTest", "ScalaCheck", "Specs2", "UTest", "JUnit")
  val frameworks10 = Array("UTest", "JUnit")

  def compileBeforeTesting(buildName: String, target: String): (State, Project, CompileAnalysis) = {
    import bloop.util.JavaCompat.EnrichOptional
    val state0 = TestUtil.loadTestProject(buildName)
    val project = state0.build.getProjectFor(target).getOrElse(sys.error(s"Missing $target!"))
    val order = CompileMode.Sequential
    val cwd = state0.build.origin.getParent
    val format = ReporterConfig.defaultFormat
    val createReporter = (project: Project, cwd: AbsolutePath) =>
      CompilationTask.toReporter(project, cwd, format, state0.logger)
    val dag = state0.build.getDagFor(project)
    val compileTask =
      CompilationTask.compile(state0, dag, createReporter, order, false, false)
    val state = Await.result(compileTask.runAsync(ExecutionContext.scheduler), Duration.Inf)
    val result = state.results.lastSuccessfulResultOrEmpty(project).analysis().toOption
    val analysis = result.getOrElse(sys.error(s"$target lacks analysis after compilation!?"))
    (state, project, analysis)
  }

  private val (testState0: State, testProject0: Project, testAnalysis0: CompileAnalysis) = {
    // 0.6 contains a scala build for 2.11.x
    compileBeforeTesting("cross-test-build-0.6", "test-projectJS-test")
  }

  private val (testState1: State, testProject1: Project, testAnalysis1: CompileAnalysis) = {
    // 1.0 contains a scala build for 2.12.x
    compileBeforeTesting("cross-test-build-1.0", "test-projectJS-test")
  }

  @Parameters
  def data(): util.Collection[Array[Object]] = {
    val firstBatch = Array(Array[AnyRef](frameworks06, testState0, testProject0, testAnalysis0))
    val secondBatch = Array(Array[AnyRef](frameworks10, testState1, testProject1, testAnalysis1))
    util.Arrays.asList((firstBatch ++ secondBatch): _*)
  }
}

@Category(Array(classOf[bloop.SlowTests]))
@RunWith(classOf[Parameterized])
class JsTestSpec(
    targetFrameworks: Array[String],
    testState: State,
    testProject: Project,
    testAnalysis: CompileAnalysis
) {

  @Test
  def testSuffixCanBeOmitted(): Unit = {
    val expectedName = testProject.name
    val withoutSuffix = Tasks.pickTestProject(expectedName.stripSuffix("-test"), testState)
    val withSuffix = Tasks.pickTestProject(expectedName, testState)
    assertTrue("Couldn't find the project without suffix", withoutSuffix.isDefined)
    assertEquals(expectedName, withoutSuffix.get.name)
    assertTrue("Couldn't find the project with suffix", withSuffix.isDefined)
    assertEquals(expectedName, withSuffix.get.name)
  }

  @Test
  def canRunTest(): Unit = {
    val logger = new RecordingLogger()
    val blockingDuration = Duration.apply(15, TimeUnit.SECONDS)
    val action = Run(Commands.Test(projects = List(testProject.name)))
    val resultingState: State =
      TestUtil.blockingExecute(action, testState.copy(logger = logger), blockingDuration)
    assertTrue(
      s"Run failed: ${logger.getMessages.mkString("\n")}",
      resultingState.status.isOk
    )

    val expectedMsgs =
      targetFrameworks.toList.map(framework => s"All tests in hello.${framework}Test passed")
    val missingBuffer = ListBuffer.newBuilder[String]
    val loggerMsgs = logger.getMessages().filter(_._1 == "info").map(_._2)
    expectedMsgs.foreach { expectedMsg =>
      if (loggerMsgs.contains(expectedMsg)) ()
      else missingBuffer.+=(expectedMsg)
    }

    val missingMsgs = missingBuffer.result().toList
    Assert.assertFalse("Expected msgs are empty", expectedMsgs.isEmpty)
    Assert.assertTrue(s"Missing msgs in logs ${missingMsgs}", missingMsgs.isEmpty)
  }

  @Test
  def canCancelTestFramework(): Unit = {
    val logger = new RecordingLogger()
    val testState1 = testState.copy(logger = logger)
    var hasErrors: Boolean = false
    val testEventHandler = new LoggingEventHandler(testState.logger)
    val failureHandler = new LoggingEventHandler(testState.logger) {
      override def report(): Unit = testEventHandler.report()
      override def handle(event: TestSuiteEvent): Unit = {
        testEventHandler.handle(event)
        event match {
          case TestSuiteEvent.Results(_, ev)
              if ev.exists(e => Tasks.TestFailedStatus.contains(e.status())) =>
            hasErrors = true
          case _ => ()
        }
      }
    }

    // Remove project-level exclusions so that we can run and cancel `EternalUtest`
    val newTestProject: Project = {
      val opts = testProject.testOptions
      val newExcludes = opts.excludes.filter(_.contains("Writing"))
      testProject.copy(testOptions = opts.copy(excludes = newExcludes))
    }

    val cwd = testState.commonOptions.workingPath
    val testFilter = TestInternals.parseFilters(Nil)

    val testHandle = TestTask
      .runTestSuites(testState1, newTestProject, cwd, Nil, testFilter, failureHandler)
      .runAsync(ExecutionContext.ioScheduler)
    val driver: Cancelable = {
      // The wait is long because it has to link and run all tests until it runs the infinite test
      val cancelTime = Duration.apply(8000, TimeUnit.MILLISECONDS)
      ExecutionContext.ioScheduler.scheduleOnce(cancelTime) { testHandle.cancel() }
    }

    try {
      val exitCode = Await.result(testHandle, Duration.apply(20, TimeUnit.SECONDS))
      Assert.assertFalse("There can be no failures during execution", hasErrors)
      Assert.assertTrue("The exit code must be successful", exitCode == 0)

      val loggerMsgs = logger.getMessages
      val errorMsgs = loggerMsgs.filter(_._1 == "error").map(_._2)
      Assert.assertTrue("There can be no errors printed to the user", errorMsgs.isEmpty)

      // Only test this in the 1.0 test infrastructure as we use our own process infrastructure
      if (testProject.baseDirectory.syntax.contains("1.0")) {
        // Check that we are indeed destroying the process by invoking run.close in `BloopComRunner`
        val destructionWitnesses =
          loggerMsgs.filter(_._1 == "debug").map(_._2).filter(_.contains("Destroying process")).size
        if (destructionWitnesses == 0) Assert.fail("The underlying process was not killed")
        else if (destructionWitnesses > 1) Assert.fail("The process was killed more than once!")
        else () // Everything is fine if it has only been killed once :)
      }
    } catch {
      case NonFatal(t) =>
        driver.cancel()
        testHandle.cancel()
        logger.dump()

        t match {
          case e: ExecutionException => throw e.getCause
          case _ => throw t
        }
    }
  }

  @Test
  def testsAreDetected(): Unit = {
    // Load the project's classpath by filtering out unwanted FQNs to create the test loader
    val classpath = testProject.fullClasspathFor(testState.build.getDagFor(testProject))
    val classpathEntries = classpath.map(_.underlying.toUri.toURL)
    val testLoader = new URLClassLoader(classpathEntries, Some(TestInternals.filteredLoader).orNull)
    def frameworks(classLoader: ClassLoader): List[Framework] = {
      testProject.testFrameworks.flatMap(f =>
        TestInternals.loadFramework(classLoader, f.names, testState.logger))
    }

    TestUtil.quietIfSuccess(testState.logger) { logger =>
      val discoveredTask = TestTask.discoverTestFrameworks(testProject, testState).map {
        case Some(discoveredTestFrameworks) =>
          val testNames = TestTask
            .discoverTests(testAnalysis, frameworks(testLoader))
            .valuesIterator
            .flatMap(defs => defs.map(_.fullyQualifiedName()))
            .toList
          targetFrameworks.foreach { framework =>
            assertTrue(
              s"Test not detected for $framework.",
              testNames.exists(_.contains(s"${framework}Test"))
            )
          }

        case None => Assert.fail(s"Missing discovered tests in ${testProject}")
      }

      try {
        TestUtil.blockOnTask(discoveredTask, 10)
      } finally {
        testLoader.close()
      }
      ()
    }
  }
}
