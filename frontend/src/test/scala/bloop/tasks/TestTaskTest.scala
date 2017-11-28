package bloop.tasks

import bloop.DynTest
import bloop.logging.Logger
import sbt.testing.{Runner, TaskDef}

import scala.concurrent.ExecutionContext.Implicits.global

object TestTaskTest extends DynTest {

  private val logger = Logger.get

  val frameworks = List("ScalaTest", "ScalaCheck", "Specs2", "UTest")

  frameworks.foreach { framework =>
    test(framework + " tests are detected") {
      logger.quietIfSuccess { logger =>
        val testNames = getTestNames("with-tests", "with-tests-test", logger)
        assert(testNames.exists(_.contains(framework + "Test")))
      }
    }
  }

  frameworks.foreach { framework =>
    test(framework + " tests can run") {
      logger.quietIfSuccess { logger =>
        val projectName = "with-tests"
        val moduleName = projectName + "-test"
        val tasks = getTestTasks("with-tests", "with-tests-test", logger)
        val testClassLoader = tasks.getTestLoader(moduleName)
        val definedTests = tasks
          .definedTests(moduleName, testClassLoader)
          .map {
            case (runner, taskDefs) =>
              (runner, taskDefs.filter(_.fullyQualifiedName().contains(framework + "Test")))
          }
          .filter(_._2.nonEmpty)

        assert(definedTests.size == 1)
        definedTests.foreach {
          case (runner, taskDefs) => tasks.runTests(runner(), taskDefs.toArray)
        }
      }
    }
  }

  private def getTestTasks(projectName: String, moduleName: String, logger: Logger): TestTasks = {
    val testProject = {
      val projects = ProjectHelpers.loadTestProject(projectName, logger)
      val tasks = CompilationTasks(projects, CompilationHelpers.compilerCache, logger)
      tasks.parallelCompile(projects(moduleName))
    }

    new TestTasks(testProject, logger)
  }

  private def getTests(projectName: String,
                       moduleName: String,
                       logger: Logger): Seq[(() => Runner, Seq[TaskDef])] = {
    val tasks = getTestTasks(projectName, moduleName, logger)
    val testClassLoader = tasks.getTestLoader(moduleName)

    tasks.definedTests(moduleName, testClassLoader)
  }

  private def getTestNames(projectName: String, moduleName: String, logger: Logger): Seq[String] = {
    val definedTests = getTests(projectName, moduleName, logger)
    for {
      (_, tasks) <- definedTests
      task <- tasks
    } yield task.fullyQualifiedName()
  }

}
