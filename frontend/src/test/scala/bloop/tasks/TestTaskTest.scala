package bloop.tasks

import bloop.{DynTest, Project}
import bloop.engine.State
import bloop.exec.{InProcess, JavaEnv, ProcessConfig}
import bloop.reporter.ReporterConfig
import sbt.testing.Framework
import bloop.engine.tasks.Tasks
import bloop.testing.{DiscoveredTests, TestInternals}
import xsbti.compile.CompileAnalysis

object TestTaskTest extends DynTest {
  private val TestProjectName = "with-tests"
  private val (testState: State, testProject: Project, testAnalysis: CompileAnalysis) = {
    import bloop.util.JavaCompat.EnrichOptional
    val target = s"$TestProjectName-test"
    val state0 = ProjectHelpers.loadTestProject(TestProjectName)
    val project = state0.build.getProjectFor(target).getOrElse(sys.error(s"Missing $target!"))
    val state = Tasks.compile(state0, project, ReporterConfig.defaultFormat)
    val result = state.results.getResult(project).analysis().toOption
    val analysis = result.getOrElse(sys.error(s"$target lacks analysis after compilation!?"))
    (state, project, analysis)
  }

  test("Test project can be selected with or without `-test` suffix") {
    val withoutSuffix = Tasks.pickTestProject(TestProjectName, testState)
    val withSuffix = Tasks.pickTestProject(s"$TestProjectName-test", testState)
    assert(withoutSuffix.isDefined)
    assert(withoutSuffix.get.name == "with-tests-test")
    assert(withSuffix.isDefined)
    assert(withSuffix.get.name == "with-tests-test")
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

  private def runTestFramework(frameworkName: String, fork: Boolean): Unit = {
    testState.logger.quietIfSuccess { logger =>
      val config = processRunnerConfig(fork)
      val classLoader = testLoader(config)
      val discovered = Tasks.discoverTests(testAnalysis, frameworks(classLoader)).toList
      val tests = discovered.flatMap {
        case (framework, taskDefs) =>
          val testName = s"${frameworkName}Test"
          val filteredDefs = taskDefs.filter(_.fullyQualifiedName.contains(testName))
          Seq(framework -> filteredDefs)
      }.toMap
      val discoveredTests = DiscoveredTests(classLoader, tests)
      TestInternals.executeTasks(config, discoveredTests, Tasks.eventHandler, logger)
    }
  }

  // Test that frameworks are class-loaded, detected and that test classes exist and can be run.
  val frameworkNames = List("ScalaTest", "ScalaCheck", "Specs2", "UTest")
  frameworkNames.foreach { framework =>
    test(s"$framework's tests are detected") {
      testState.logger.quietIfSuccess { logger =>
        val config = processRunnerConfig(fork = false)
        val classLoader = testLoader(config)
        val discovered = Tasks.discoverTests(testAnalysis, frameworks(classLoader))
        val testNames = discovered.valuesIterator.flatMap(defs => defs.map(_.fullyQualifiedName()))
        assert(testNames.exists(_.contains(s"${framework}Test")))
      }
    }

    test(s"$framework's tests can be run in process") {
      runTestFramework(framework, fork = false)
    }

    test(s"$framework's tests can be run in a forked JVM") {
      runTestFramework(framework, fork = true)
    }
  }
}
