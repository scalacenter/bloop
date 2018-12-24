package bloop.tasks

import java.util.concurrent.TimeUnit
import java.util.{Arrays, Collection}

import bloop.CompileMode
import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.{Assert, Test}
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
import bloop.engine.tasks.{CompilationTask, Tasks, TestTask}
import bloop.testing.{DiscoveredTestFrameworks, NoopEventHandler, TestInternals}
import monix.execution.misc.NonFatal
import xsbti.compile.CompileAnalysis

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object JvmTestSpec {
  // Test that frameworks are class-loaded, detected and that test classes exist and can be run.
  val frameworks = Array("ScalaTest", "ScalaCheck", "Specs2", "UTest", "JUnit")

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
      CompilationTask.compile(state0, dag, createReporter, false, order, false, false)
    val state = Await.result(compileTask.runAsync(ExecutionContext.scheduler), Duration.Inf)
    val result = state.results.lastSuccessfulResultOrEmpty(project).analysis().toOption
    val analysis = result.getOrElse(sys.error(s"$target lacks analysis after compilation!?"))
    (state, project, analysis)
  }

  private val (testState0: State, testProject0: Project, testAnalysis0: CompileAnalysis) = {
    // 0.6 contains a scala build for 2.11.x
    compileBeforeTesting("cross-test-build-0.6", "test-project-test")
  }

  private val (testState1: State, testProject1: Project, testAnalysis1: CompileAnalysis) = {
    // 1.0 contains a scala build for 2.12.x
    compileBeforeTesting("cross-test-build-1.0", "test-project-test")
  }

  @Parameters
  def data(): Collection[Array[Object]] = {
    val firstBatch = Array(Array[AnyRef](frameworks, testState0, testProject0, testAnalysis0))
    val secondBatch = Array(Array[AnyRef](frameworks, testState1, testProject1, testAnalysis1))
    Arrays.asList((firstBatch ++ secondBatch): _*)
  }
}

@Category(Array(classOf[bloop.SlowTests]))
@RunWith(classOf[Parameterized])
class JvmTestSpec(
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

  private val processRunnerConfig: Forker = {
    val javaEnv = JavaEnv.default
    val classpath = testProject.fullClasspathFor(testState.build.getDagFor(testProject))
    val classpathEntries = classpath.map(_.underlying.toUri.toURL)
    Forker(javaEnv, classpath)
  }

  private def loaderForTestCode(fork: Forker): ClassLoader = {
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
        val classLoader = loaderForTestCode(config)
        val discovered = TestTask.discoverTests(testAnalysis, frameworks(classLoader)).toList
        val tests = discovered.flatMap {
          case (framework, taskDefs) =>
            val testName = s"${framework}Test"
            val filteredDefs = taskDefs.filter(_.fullyQualifiedName.contains(testName))
            Seq(framework -> filteredDefs)
        }.toMap
        val opts = CommonOptions.default.copy(env = TestUtil.runAndTestProperties)
        val exitCode = TestUtil.await(Duration.apply(15, TimeUnit.SECONDS)) {
          TestInternals.execute(
            cwd,
            config,
            classLoader,
            tests,
            Nil,
            Nil,
            NoopEventHandler,
            logger,
            opts)
        }
        Assert.assertTrue(s"The exit code is non-zero ${exitCode}", exitCode == 0)
      }
    }
  }

  @Test
  def canCancelTestFramework(): Unit = {
    TestUtil.withTemporaryDirectory { tmp =>
      TestUtil.quietIfSuccess(testState.logger) { logger =>
        val cwd = AbsolutePath(tmp)
        val config = processRunnerConfig
        val classLoader = loaderForTestCode(config)
        val discovered = TestTask.discoverTests(testAnalysis, frameworks(classLoader)).toList
        val tests = discovered.flatMap {
          case (framework, taskDefs) =>
            val testName = s"${framework}Test"
            val filteredDefs = taskDefs.filter(_.fullyQualifiedName.contains(testName))
            Seq(framework -> filteredDefs)
        }.toMap
        val opts = CommonOptions.default.copy(env = TestUtil.runAndTestProperties)

        val cancelTime = Duration.apply(1, TimeUnit.SECONDS)
        def createTestTask =
          TestInternals.execute(
            cwd,
            config,
            classLoader,
            tests,
            Nil,
            Nil,
            NoopEventHandler,
            logger,
            opts)

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
      val discoveredTask = TestTask.discoverTestFrameworks(testProject, testState).map {
        case Some(DiscoveredTestFrameworks.Jvm(_, _, testLoader)) =>
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

        case _ => Assert.fail(s"Missing discovered tests in ${testProject}")
      }

      TestUtil.blockOnTask(discoveredTask, 3)
      ()
    }
  }
}
