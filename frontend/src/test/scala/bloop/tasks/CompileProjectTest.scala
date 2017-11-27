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
    val rootProjectName = "bloop-test-root"
    val projects = {
      val projects = ProjectHelpers.loadTestProject(testDirectory.getFileName.toString, logger)
      val rootProject = Project(
        name = rootProjectName,
        dependencies = projects.keys.toArray,
        scalaInstance = projects.head._2.scalaInstance,
        classpath = Array.empty,
        classesDir = AbsolutePath(testDirectory),
        scalacOptions = Array.empty,
        javacOptions = Array.empty,
        sourceDirectories = Array.empty,
        previousResult = CompilationHelpers.emptyPreviousResult,
        testFrameworks = Array.empty,
        tmp = AbsolutePath(testDirectory),
        origin = None
      )
      projects + (rootProjectName -> rootProject)
    }

    val tasks = new CompilationTasks(projects, compilerCache, logger)
    val _ = tasks.parallelCompile(projects(rootProjectName))
  }

}
