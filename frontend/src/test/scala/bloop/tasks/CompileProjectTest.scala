package bloop.tasks

import scala.concurrent.ExecutionContext.Implicits.global

import bloop.{DynTest, Project, QuietLogger}
import bloop.io.AbsolutePath

import java.nio.file.{Files, Path, Paths}

import CompilationHelpers._

object CompileProjectTest extends DynTest {

  val base = getClass.getClassLoader.getResources("projects") match {
    case res if res.hasMoreElements => Paths.get(res.nextElement.getFile)
    case _                          => throw new Exception("No projects to test?")
  }

  val projects = Files.list(base)

  projects.forEach { testDirectory =>
    test(testDirectory.getFileName.toString) {
      testProject(testDirectory)
    }
  }

  private def testProject(testDirectory: Path): Unit = {
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
      val projects = Project.fromDir(AbsolutePath(configDir)).mapValues(rebase)
      val rootProject = Project(
        name = rootProjectName,
        dependencies = projects.keySet.filterNot(_ endsWith "-test").toArray,
        scalaInstance = projects.head._2.scalaInstance,
        classpath = Array.empty,
        classesDir = AbsolutePath(baseDirectory),
        scalacOptions = Array.empty,
        javacOptions = Array.empty,
        sourceDirectories = Array.empty,
        previousResult = emptyPreviousResult,
        tmp = AbsolutePath(baseDirectory),
        origin = None
      )
      projects + (rootProjectName -> rootProject)
    }

    val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
    val _ = tasks.parallelCompile(projects(rootProjectName))
  }

}
