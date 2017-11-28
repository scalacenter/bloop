package bloop.tasks

import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.file.{Files, Path, Paths}

import bloop.{DynTest, Project}
import bloop.io.AbsolutePath
import bloop.logging.Logger
import bloop.util.TopologicalSort

object IntegrationTestSuite extends DynTest {
  val logger = new Logger("bloop-test")
  val compilerCache = CompilationHelpers.compilerCache(logger)
  val projects = Files.list(getClass.getClassLoader.getResources("projects") match {
    case res if res.hasMoreElements => Paths.get(res.nextElement.getFile)
    case _ => throw new Exception("No projects to test?")
  })

  projects.forEach { testDirectory =>
    test(testDirectory.getFileName.toString) {
      testProject(testDirectory, logger)
    }
  }

  private def testProject(testDirectory: Path, logger: Logger): Unit = {
    val rootProjectName = "bloop-test-root"
    val classesDir = AbsolutePath(testDirectory)
    val projects = {
      val projects = ProjectHelpers.loadTestProject(testDirectory.getFileName.toString, logger)
      val rootProject = Project(
        name = rootProjectName,
        dependencies = projects.keys.toArray,
        scalaInstance = projects.head._2.scalaInstance,
        classpath = Array.empty,
        classesDir = classesDir,
        scalacOptions = Array.empty,
        javacOptions = Array.empty,
        sourceDirectories = Array.empty,
        previousResult = CompilationHelpers.emptyPreviousResult,
        tmp = classesDir,
        testFrameworks = Array.empty,
        origin = None
      )
      projects + (rootProjectName -> rootProject)
    }

    // Remove classes from previous runs in the tmp directory
    if (Files.exists(classesDir.underlying))
      Files
        .newDirectoryStream(classesDir.underlying, "*.class")
        .iterator
        .forEachRemaining(p => Files.delete(p))

    assert(projects.forall { case (_, p) => ProjectHelpers.noPreviousResult(p) })
    val tasks = new CompilationTasks(projects, compilerCache, logger)
    val newProjects = tasks.parallelCompile(projects(rootProjectName))
    val reachableProjects = TopologicalSort.reachable(newProjects(rootProjectName), newProjects)
    assert(reachableProjects.forall { case (_, p) => ProjectHelpers.hasPreviousResult(p) })
  }
}
