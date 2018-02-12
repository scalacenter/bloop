package bloop.tasks

import java.nio.file.{Files, Path, Paths}
import java.util.{Arrays, Collection}

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Dag, Exit, Interpreter, Run}
import bloop.exec.JavaEnv
import bloop.Project
import bloop.io.AbsolutePath

object IntegrationTestSuite {
  val projects = ProjectHelpers.testProjectsIndex.map(_._2).toArray.map(Array.apply(_))

  @Parameters
  def data() = {
    Arrays.asList(projects: _*)
  }
}

@Category(Array(classOf[bloop.SlowTests]))
@RunWith(classOf[Parameterized])
class IntegrationTestSuite(testDirectory: Path) {
  val integrationTestName = testDirectory.getParent.getFileName.toString

  @Test
  def compileProject: Unit = {
    if (sys.env.get("RUN_COMMUNITY_BUILD").isEmpty) ()
    else compileProject0
  }

  def compileProject0: Unit = {
    val state0 = ProjectHelpers.loadTestProject(testDirectory, integrationTestName)
    val (state, projectToCompile) = getModuleToCompile(testDirectory) match {
      case Some(projectName) =>
        (state0, state0.build.getProjectFor(projectName).get)

      case None =>
        val rootProjectName = "bloop-test-root"
        val fakeConfigDir = AbsolutePath(testDirectory.resolve(s"$rootProjectName"))
        val classesDir = AbsolutePath(testDirectory)
        val previousProjects = state0.build.projects
        val javaEnv = JavaEnv.default

        val rootProject = Project(
          name = rootProjectName,
          baseDirectory = AbsolutePath(testDirectory),
          dependencies = previousProjects.map(_.name).toArray,
          scalaInstance = previousProjects.head.scalaInstance,
          rawClasspath = Array.empty,
          classesDir = classesDir,
          scalacOptions = Array.empty,
          javacOptions = Array.empty,
          sourceDirectories = Array.empty,
          tmp = classesDir,
          testFrameworks = Array.empty,
          javaEnv = javaEnv,
          bloopConfigDir = classesDir
        )
        val state =
          state0.copy(build = state0.build.copy(projects = rootProject :: previousProjects))

        (state, rootProject)
    }

    val reachable = Dag.dfs(state.build.getDagFor(projectToCompile)).filter(_ != projectToCompile)
    reachable.foreach(removeClassFiles)
    reachable.foreach { p =>
      assertTrue(s"Project `$integrationTestName/${p.name}` is already compiled.",
                 ProjectHelpers.noPreviousResult(p, state))
    }

    val action =
      Run(Commands.Compile(projectToCompile.name, incremental = true), Exit(ExitStatus.Ok))
    val state1 = Interpreter.execute(action, state)
    reachable.foreach { p =>
      assertTrue(s"Project `$integrationTestName/${p.name}` has not been compiled.",
                 ProjectHelpers.hasPreviousResult(p, state1))
    }
  }

  private def removeClassFiles(p: Project): Unit = {
    val classesDirPath = p.classesDir.underlying
    if (Files.exists(classesDirPath)) {
      Files.newDirectoryStream(classesDirPath, "*.class").forEach(p => Files.delete(p))
    }
  }

  private def getModuleToCompile(testDirectory: Path): Option[String] = {
    import scala.collection.JavaConverters._
    val toCompile = testDirectory.resolve("module-to-compile.txt")

    if (Files.exists(toCompile)) Files.readAllLines(toCompile).asScala.headOption
    else None
  }
}
