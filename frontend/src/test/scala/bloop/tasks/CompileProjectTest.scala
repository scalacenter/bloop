package bloop.tasks

import scala.concurrent.ExecutionContext.Implicits.global
import java.nio.file.{Files, Path, Paths}

import bloop.{DynTest, Project}
import bloop.io.AbsolutePath
import bloop.logging.Logger

object CompileProjectTest extends DynTest {

  val logger = new Logger("bloop-test")
  val compilerCache = CompilationHelpers.compilerCache(logger)

  val base = getClass.getClassLoader.getResources("projects") match {
    case res if res.hasMoreElements => Paths.get(res.nextElement.getFile)
    case _ => throw new Exception("No projects to test?")
  }

  val projects = Files.list(base)

  projects.forEach { testDirectory =>
    test(testDirectory.getFileName.toString) {
      logger.quietIfSuccess { logger =>
        testProject(testDirectory, logger)
      }
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
    val projects = {
      val projects = Project.fromDir(AbsolutePath(configDir), logger).mapValues(rebase)
      val rootProject = Project(
        name = rootProjectName,
        dependencies = projects.keys.toArray,
        scalaInstance = projects.head._2.scalaInstance,
        classpath = Array.empty,
        classesDir = AbsolutePath(baseDirectory),
        scalacOptions = Array.empty,
        javacOptions = Array.empty,
        sourceDirectories = Array.empty,
        previousResult = CompilationHelpers.emptyPreviousResult,
        testFrameworks = Array.empty,
        tmp = AbsolutePath(baseDirectory),
        origin = None
      )
      projects + (rootProjectName -> rootProject)
    }

    val tasks = new CompilationTasks(projects, compilerCache, logger)
    val _ = tasks.parallelCompile(projects(rootProjectName))
  }

}
