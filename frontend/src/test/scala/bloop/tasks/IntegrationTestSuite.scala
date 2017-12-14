package bloop.tasks

import java.nio.file.{Files, Path, Paths}

import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Dag, Exit, Interpreter, Run}
import bloop.exec.JavaEnv
import bloop.{DynTest, Project}
import bloop.io.AbsolutePath
import bloop.logging.{BloopLogger, Logger}

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

  private def testProject(testDirectory: Path): Unit = {
    val rootProjectName = "bloop-test-root"
    val fakeConfigDir = AbsolutePath(testDirectory.resolve(s"rootProjectName"))
    val logger = BloopLogger.default(fakeConfigDir.toString)
    val classesDir = AbsolutePath(testDirectory)
    val state0 = ProjectHelpers.loadTestProject(testDirectory.getFileName.toString)
    val previousProjects = state0.build.projects
    val javaEnv = JavaEnv.default(fork = false)

    val rootProject = Project(
      name = rootProjectName,
      baseDirectory = AbsolutePath(testDirectory),
      dependencies = previousProjects.map(_.name).toArray,
      scalaInstance = previousProjects.head.scalaInstance,
      classpath = Array.empty,
      classesDir = classesDir,
      scalacOptions = Array.empty,
      javacOptions = Array.empty,
      sourceDirectories = Array.empty,
      tmp = classesDir,
      testFrameworks = Array.empty,
      javaEnv = javaEnv,
      bloopConfigDir = classesDir
    )

    val state = state0.copy(build = state0.build.copy(projects = rootProject :: previousProjects))
    val reachable = Dag.dfs(state.build.getDagFor(rootProject)).filter(_ != rootProject)
    reachable.foreach(removeClassFiles)
    assert(reachable.forall(p => ProjectHelpers.noPreviousResult(p, state)))

    val action = Run(Commands.Compile(rootProjectName, incremental = true), Exit(ExitStatus.Ok))
    val state1 = Interpreter.execute(action, state)
    assert(reachable.forall(p => ProjectHelpers.hasPreviousResult(p, state1)))
  }
}
