package bloop.engine.tasks

import java.net.URLClassLoader

import bloop.Project
import bloop.testing.TestInternals
import sbt.testing.{Event, EventHandler, Framework, TaskDef, SuiteSelector}

object TestTasks {
  import bloop.cli.ExitStatus
  import bloop.engine.{State, Dag}
  def pickTestProject(projectName: String, state: State): Option[Project] = {
    state.build.getProjectFor(s"$projectName-test").orElse(state.build.getProjectFor(projectName))
  }

  private[bloop] val eventHandler =
    new EventHandler { override def handle(event: Event): Unit = () }

  import xsbt.api.Discovery
  import xsbti.compile.CompileAnalysis
  private type Discovered = Map[Framework, List[TaskDef]]
  private[bloop] def discoverTests(analysis: CompileAnalysis,
                                   frameworks: Array[Framework]): Discovered = {
    import scala.collection.mutable
    val (subclassPrints, annotatedPrints) = TestInternals.getFingerprints(frameworks)
    val definitions = TestInternals.potentialTests(analysis)
    val discovered = Discovery(subclassPrints.map(_._1), annotatedPrints.map(_._1))(definitions)
    val tasks = mutable.Map.empty[Framework, mutable.Buffer[TaskDef]]
    frameworks.foreach(tasks(_) = mutable.Buffer.empty)
    discovered.foreach {
      case (defn, discovered) =>
        TestInternals.matchingFingerprints(subclassPrints, annotatedPrints, discovered).foreach {
          case (_, _, framework, fingerprint) =>
            tasks(framework) += new TaskDef(defn.name, fingerprint, false, Array(new SuiteSelector))
        }
    }
    tasks.mapValues(_.toList).toMap
  }

  private[bloop] def constructClasspath(project: Project): Array[java.net.URL] =
    project.fullClasspath.map(_.underlying.toUri.toURL())

  def test(state: State, project: Project, aggregate: Boolean): State = {
    // TODO(jvican): This method should cache the test loader always.
    import state.logger
    import bloop.util.JavaCompat.EnrichOptional

    val projectsToTest = if (aggregate) Dag.dfs(state.build.getDagFor(project)) else List(project)
    projectsToTest.foreach { project =>
      val projectName = project.name
      val classpath = constructClasspath(project)
      val testLoader = new URLClassLoader(classpath, TestInternals.filteredLoader)
      val frameworks = project.testFrameworks
        .flatMap(fname => TestInternals.getFramework(testLoader, fname.toList, logger))
      logger.debug(s"Found frameworks: ${frameworks.map(_.name).mkString(", ")}")
      val analysis = state.results.getResult(project).analysis().toOption.getOrElse {
        logger.warn(s"Test execution is triggered but no compilation detected for ${projectName}.")
        sbt.internal.inc.Analysis.empty
      }

      val discoveredTests = discoverTests(analysis, frameworks)
      val allTestNames: List[String] =
        discoveredTests.valuesIterator.flatMap(defs => defs.map(_.fullyQualifiedName())).toList
      logger.debug(s"Bloop found the following tests for ${projectName}: $allTestNames.")
      discoveredTests.iterator.foreach {
        case (framework, taskDefs) =>
          val runner = TestInternals.getRunner(framework, testLoader)
          val tasks = runner.tasks(taskDefs.toArray).toList
          TestInternals.executeTasks(tasks, eventHandler, logger)
          val _ = runner.done() // TODO(jvican): We've never used this value, what is it?
      }
    }

    // Return the previous state, test execution doesn't modify it.
    state.mergeStatus(ExitStatus.Ok)
  }
}
