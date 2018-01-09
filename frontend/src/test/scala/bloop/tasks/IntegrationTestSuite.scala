package bloop.tasks

import java.nio.file.{Files, Path, Paths}

import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Dag, Exit, Interpreter, Run}
import bloop.exec.JavaEnv
import bloop.{DynTest, Project}
import bloop.io.AbsolutePath

object IntegrationTestSuite extends DynTest {
  val projects = Files.list(getClass.getClassLoader.getResources("projects") match {
    case res if res.hasMoreElements => Paths.get(res.nextElement.getFile)
    case _ => throw new Exception("No projects to test?")
  })

  projects.forEach { testDirectory =>
    test(testDirectory.getFileName.toString) {
      testProject(testDirectory)
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

  private def testProject(testDirectory: Path): Unit = {

    val state0 = ProjectHelpers.loadTestProject(testDirectory.getFileName.toString)
    val (state, projectToCompile) = getModuleToCompile(testDirectory) match {
      case Some(projectName) =>
        (state0, state0.build.getProjectFor(projectName).get)

      case None =>
        val rootProjectName = "bloop-test-root"
        val fakeConfigDir = AbsolutePath(testDirectory.resolve(s"$rootProjectName"))
        val classesDir = AbsolutePath(testDirectory)
        val previousProjects = state0.build.projects
        val javaEnv = JavaEnv.default(fork = false)

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
    assert(reachable.forall(p => ProjectHelpers.noPreviousResult(p, state)))

    val action =
      Run(Commands.Compile(projectToCompile.name, incremental = true), Exit(ExitStatus.Ok))
    val state1 = Interpreter.execute(action, state)
    assert(reachable.forall(p => ProjectHelpers.hasPreviousResult(p, state1)))
  }
}
