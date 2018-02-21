package bloop.tasks

import java.util.{Arrays, Collection}

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters
import bloop.Project
import bloop.engine.State
import bloop.exec.{JavaEnv, ProcessConfig}
import bloop.reporter.ReporterConfig
import sbt.testing.Framework
import bloop.engine.tasks.Tasks
import bloop.testing.{DiscoveredTests, TestInternals}
import xsbti.compile.CompileAnalysis

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object TestTaskTest {
  // Test that frameworks are class-loaded, detected and that test classes exist and can be run.
  val frameworkNames = Array("ScalaTest", "ScalaCheck", "Specs2", "UTest")

  @Parameters
  def data(): Collection[Array[String]] = {
    Arrays.asList(frameworkNames.map(Array.apply(_)): _*)
  }
}

@Category(Array(classOf[bloop.SlowTests]))
@RunWith(classOf[Parameterized])
class TestTaskTest(framework: String) {
  private val TestProjectName = "with-tests"
  private val (testState: State, testProject: Project, testAnalysis: CompileAnalysis) = {
    import bloop.util.JavaCompat.EnrichOptional
    val target = s"$TestProjectName-test"
    val state0 = ProjectHelpers.loadTestProject(TestProjectName)
    val project = state0.build.getProjectFor(target).getOrElse(sys.error(s"Missing $target!"))
    val compileTask = Tasks.compile(state0, project, ReporterConfig.defaultFormat)
    val state = Await.result(compileTask.runAsync(state0.scheduler), Duration.Inf)
    val result = state.results.getResult(project).analysis().toOption
    val analysis = result.getOrElse(sys.error(s"$target lacks analysis after compilation!?"))
    (state, project, analysis)
  }

  @Test
  def testSuffixCanBeOmitted = {
    val expectedName = TestProjectName + "-test"
    val withoutSuffix = Tasks.pickTestProject(TestProjectName, testState)
    val withSuffix = Tasks.pickTestProject(expectedName, testState)
    assertTrue("Couldn't find the project without suffix", withoutSuffix.isDefined)
    assertEquals(expectedName, withoutSuffix.get.name)
    assertTrue("Couldn't find the project with suffix", withSuffix.isDefined)
    assertEquals(expectedName, withSuffix.get.name)
  }

  private def processRunnerConfig(fork: Boolean): ProcessConfig = {
    val javaEnv = JavaEnv.default(fork)
    val classpath = testProject.classpath
    ProcessConfig(javaEnv, classpath)
  }

  private def testLoader(processConfig: ProcessConfig): ClassLoader =
    processConfig.toExecutionClassLoader(Some(TestInternals.filteredLoader))

  private def frameworks(classLoader: ClassLoader): Array[Framework] = {
    testProject.testFrameworks.flatMap(n =>
      TestInternals.getFramework(classLoader, n.toList, testState.logger))
  }

  private def runTestFramework(fork: Boolean): Unit = {
    testState.logger.quietIfSuccess { logger =>
      val config = processRunnerConfig(fork)
      val classLoader = testLoader(config)
      val discovered = Tasks.discoverTests(testAnalysis, frameworks(classLoader)).toList
      val tests = discovered.flatMap {
        case (framework, taskDefs) =>
          val testName = s"${framework}Test"
          val filteredDefs = taskDefs.filter(_.fullyQualifiedName.contains(testName))
          Seq(framework -> filteredDefs)
      }.toMap
      val discoveredTests = DiscoveredTests(classLoader, tests)
      TestInternals.executeTasks(config, discoveredTests, Tasks.eventHandler, logger)
    }
  }

  @Test
  def testsAreDetected = {
    testState.logger.quietIfSuccess { logger =>
      val config = processRunnerConfig(fork = false)
      val classLoader = testLoader(config)
      val discovered = Tasks.discoverTests(testAnalysis, frameworks(classLoader))
      val testNames = discovered.valuesIterator.flatMap(defs => defs.map(_.fullyQualifiedName()))
      assertTrue(s"Test not detected for $framework.",
                 testNames.exists(_.contains(s"${framework}Test")))
    }
  }

  @Test
  def testsCanRunInProcess = runTestFramework(fork = false)

  @Test
  def testsCanRunInForkedJVM = runTestFramework(fork = true)
}
