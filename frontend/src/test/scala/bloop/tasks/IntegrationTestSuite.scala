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
    val configDir = testDirectory.resolve("bloop-config")
    val baseDirectoryFile = configDir.resolve("base-directory")
    assert(Files.exists(configDir) && Files.exists(baseDirectoryFile))
    val baseDirectory = {
      val contents = Files.readAllLines(baseDirectoryFile)
      assert(!contents.isEmpty)
      Paths.get(contents.get(0))
    }

    def rebase(proj: Project) = ProjectHelpers.rebase(baseDirectory, testDirectory, proj)
    val rootProjectName = "bloop-test-root"
    val classesDir = AbsolutePath(baseDirectory)
    val projects = {
      val projects = Project.fromDir(AbsolutePath(configDir), logger).mapValues(rebase)
      val rootProject = Project(
        name = rootProjectName,
        dependencies = projects.keySet.filterNot(_ endsWith "-test").toArray,
        scalaInstance = projects.head._2.scalaInstance,
        classpath = Array.empty,
        classesDir = classesDir,
        scalacOptions = Array.empty,
        javacOptions = Array.empty,
        sourceDirectories = Array.empty,
        previousResult = CompilationHelpers.emptyPreviousResult,
        tmp = classesDir,
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
    println(reachableProjects.map { case (_, p) => p -> ProjectHelpers.hasPreviousResult(p) })
    assert(reachableProjects.forall { case (_, p) => ProjectHelpers.hasPreviousResult(p) })
  }
}
