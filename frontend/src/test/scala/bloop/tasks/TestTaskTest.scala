package bloop.tasks

import java.util.concurrent.TimeUnit
import java.util.{Arrays, Collection}

import bloop.CompileMode
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import bloop.data.Project
import bloop.cli.CommonOptions
import bloop.engine.{ExecutionContext, State}
import bloop.exec.{Forker, JavaEnv}
import bloop.io.AbsolutePath
import bloop.reporter.ReporterConfig
import sbt.testing.Framework
import bloop.engine.tasks.{CompilationTask, Tasks}
import bloop.testing.{DiscoveredTests, NoopEventHandler, TestInternals}
import monix.execution.misc.NonFatal
import xsbti.compile.CompileAnalysis

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object TestTaskTest {
  // Test that frameworks are class-loaded, detected and that test classes exist and can be run.
  val frameworkNames = Array("ScalaTest", "ScalaCheck", "Specs2", "UTest", "JUnit")

  private val TestProjectName = "with-tests"
  private val (testState: State, testProject: Project, testAnalysis: CompileAnalysis) = {
    import bloop.util.JavaCompat.EnrichOptional
    val target = s"$TestProjectName-test"
    val state0 = TestUtil.loadTestProject(TestProjectName)
    val project = state0.build.getProjectFor(target).getOrElse(sys.error(s"Missing $target!"))
    val format = ReporterConfig.defaultFormat
    val compileTask =
      CompilationTask.compile(state0, project, format, false, CompileMode.Sequential, false, false)
    val state = Await.result(compileTask.runAsync(ExecutionContext.scheduler), Duration.Inf)
    val result = state.results.lastSuccessfulResultOrEmpty(project).analysis().toOption
    val analysis = result.getOrElse(sys.error(s"$target lacks analysis after compilation!?"))
    (state, project, analysis)
  }

  @Parameters
  def data(): Collection[Array[Object]] = {
    Arrays.asList(
      frameworkNames.map(n => Array[AnyRef](n, testState, testProject, testAnalysis)): _*)
  }
}

@Category(Array(classOf[bloop.SlowTests]))
@RunWith(classOf[Parameterized])
class TestTaskTest(
    framework: String,
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

  private val processRunnerConfig: Forker = {
    val javaEnv = JavaEnv.default
    val classpath = testProject.classpath
    Forker(javaEnv, classpath)
  }

  private def testLoader(fork: Forker): ClassLoader = {
    fork.newClassLoader(Some(TestInternals.filteredLoader))
  }

  private def frameworks(classLoader: ClassLoader): List[Framework] = {
    testProject.testFrameworks.flatMap(f =>
      TestInternals.loadFramework(classLoader, f.names, testState.logger))
  }

  @Test
  def canRunTestFramework(): Unit = {
    TestUtil.withTemporaryDirectory { tmp =>
      TestUtil.quietIfSuccess(testState.logger) { logger =>
        val cwd = AbsolutePath(tmp)
        val config = processRunnerConfig
        val classLoader = testLoader(config)
        val discovered = Tasks.discoverTests(testAnalysis, frameworks(classLoader)).toList
        val tests = discovered.flatMap {
          case (framework, taskDefs) =>
            val testName = s"${framework}Test"
            val filteredDefs = taskDefs.filter(_.fullyQualifiedName.contains(testName))
            Seq(framework -> filteredDefs)
        }.toMap
        val discoveredTests = DiscoveredTests(classLoader, tests)
        val opts = CommonOptions.default.copy(env = TestUtil.runAndTestProperties)
        val exitCode = TestUtil.await(Duration.apply(15, TimeUnit.SECONDS)) {
          TestInternals.execute(cwd, config, discoveredTests, Nil, NoopEventHandler, logger, opts)
        }
        assert(exitCode == 0)
      }
    }
  }

  @Test
  def canCancelTestFramework(): Unit = {
    TestUtil.withTemporaryDirectory { tmp =>
      TestUtil.quietIfSuccess(testState.logger) { logger =>
        val cwd = AbsolutePath(tmp)
        val config = processRunnerConfig
        val classLoader = testLoader(config)
        val discovered = Tasks.discoverTests(testAnalysis, frameworks(classLoader)).toList
        val tests = discovered.flatMap {
          case (framework, taskDefs) =>
            val testName = s"${framework}Test"
            val filteredDefs = taskDefs.filter(_.fullyQualifiedName.contains(testName))
            Seq(framework -> filteredDefs)
        }.toMap
        val discoveredTests = DiscoveredTests(classLoader, tests)
        val opts = CommonOptions.default.copy(env = TestUtil.runAndTestProperties)

        val cancelTime = Duration.apply(1, TimeUnit.SECONDS)
        def createTestTask =
          TestInternals.execute(cwd, config, discoveredTests, Nil, NoopEventHandler, logger, opts)

        val testsTask = for {
          _ <- createTestTask
          _ <- createTestTask
          _ <- createTestTask
          state <- createTestTask
        } yield state
        val testHandle = testsTask.runAsync(ExecutionContext.ioScheduler)
        val driver = ExecutionContext.ioScheduler.scheduleOnce(cancelTime) { testHandle.cancel() }

        val exitCode = try Await.result(testHandle, Duration.apply(10, TimeUnit.SECONDS))
        catch {
          case NonFatal(t) =>
            driver.cancel()
            testHandle.cancel()
            throw t
        }

        assert(exitCode != 0)
      }
    }
  }

  @Test
  def testsAreDetected(): Unit = {
    TestUtil.quietIfSuccess(testState.logger) { logger =>
      val config = processRunnerConfig
      val classLoader = testLoader(config)
      val discovered = Tasks.discoverTests(testAnalysis, frameworks(classLoader))
      val testNames = discovered.valuesIterator.flatMap(defs => defs.map(_.fullyQualifiedName()))
      assertTrue(s"Test not detected for $framework.",
                 testNames.exists(_.contains(s"${framework}Test")))
    }
  }

}
