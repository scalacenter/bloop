package bloop
package tasks

import scala.concurrent.ExecutionContext.Implicits.global
import bloop.io.AbsolutePath

import java.util.Optional
import java.nio.file.{Files, Paths}

import xsbti.compile.{CompileAnalysis, MiniSetup, PreviousResult}

import CompilationHelpers._

object CompileProjectTest extends DynTest {

  val base = getClass.getClassLoader.getResources("projects") match {
    case res if res.hasMoreElements => Paths.get(res.nextElement.getFile)
    case _                          => ???
  }

  val projects = Files.list(base)

  projects.forEach { project =>
    test(project.getFileName.toString) {
      val configDir = project.resolve("bloop-config")
      val baseDirectoryFile = configDir.resolve("base-directory")
      assert(Files.exists(configDir) && Files.exists(baseDirectoryFile))
      val baseDirectory = {
        val contents = Files.readAllLines(baseDirectoryFile)
        assert(!contents.isEmpty)
        Paths.get(contents.get(0))
      }

      val rebase = ProjectHelpers.rebase(baseDirectory, project)
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
          previousResult =
            PreviousResult.of(Optional.empty[CompileAnalysis], Optional.empty[MiniSetup]),
          tmp = AbsolutePath(baseDirectory),
          origin = None
        )
        projects + (rootProjectName -> rootProject)
      }

      val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
      tasks.parallel(projects(rootProjectName))

    }
  }

}
