package bloop.tasks

import java.net.URLClassLoader

import bloop.Project
import bloop.logging.Logger
import bloop.testing.TestInternals
import sbt.internal.inc.Analysis
import sbt.testing._
import xsbt.api.Discovery

import scala.collection.mutable

class TestTasks(projects: Map[String, Project], logger: Logger) {

  def definedTests(projectName: String,
                   testLoader: ClassLoader): Seq[(() => Runner, Seq[TaskDef])] = {
    val project = projects(projectName)
    val frameworks =
      project.testFrameworks.flatMap(f => TestInternals.getFramework(testLoader, f.toList, logger))
    logger.debug("Found frameworks: " + frameworks.map(_.name).mkString(", "))

    val (subclassPrints, annotatedPrints) = TestInternals.getFingerprints(frameworks)

    val analysis = project.previousResult.analysis().orElse(Analysis.empty)
    val definitions = TestInternals.allDefs(analysis)

    val discovered = Discovery(subclassPrints.map(_._1), annotatedPrints.map(_._1))(definitions)
    logger.debug("Discovered tests: " + discovered.map(_._1.name()).mkString(", "))

    val tasks = mutable.Map.empty[Framework, mutable.Buffer[TaskDef]]
    frameworks.foreach(tasks(_) = mutable.Buffer.empty)
    discovered.foreach {
      case (de, di) =>
        val printInfos = TestInternals.matchingFingerprints(subclassPrints, annotatedPrints, di)
        printInfos.foreach {
          case (_, _, framework, fingerprint) =>
            val taskDef = new TaskDef(de.name, fingerprint, false, Array(new SuiteSelector))
            tasks(framework) += taskDef
        }
    }

    tasks.toSeq.map {
      case (framework, taskDefs) =>
        (() => TestInternals.getRunner(framework, testLoader)) -> taskDefs
    }
  }

  def runTests(runner: Runner, taskDefs: Array[TaskDef]): Unit = {
    val tasks = runner.tasks(taskDefs).toList
    TestInternals.executeTasks(tasks, eventHandler, logger)
  }

  def getTestLoader(projectName: String): ClassLoader = {
    val project = projects(projectName)
    val entries = (project.classesDir +: project.classpath).map(_.underlying.toUri.toURL)
    new URLClassLoader(entries, TestInternals.filteredLoader)
  }

  lazy val eventHandler = new EventHandler {
    override def handle(event: Event): Unit = ()
  }

}
