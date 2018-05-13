package bloop.tasks

import java.nio.file.{Files, Path}
import java.util.Arrays

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
import bloop.config.Config
import bloop.io.AbsolutePath
import xsbti.compile.ClasspathOptionsUtil

object IntegrationTestSuite {
  val projects = TestUtil.testProjectsIndex.map(_._2).toArray.map(Array.apply(_))

  @Parameters
  def data() = {
    Arrays.asList(projects: _*)
  }
}

@Category(Array(classOf[bloop.SlowTests]))
@RunWith(classOf[Parameterized])
class IntegrationTestSuite(testDirectory: Path) {
  val integrationTestName = TestUtil.getBaseFromConfigDir(testDirectory).getFileName.toString

  def isCommunityBuildEnabled: Boolean = {
    import scala.util.Try
    def bool(v: String): Boolean = {
      Try(java.lang.Boolean.parseBoolean(v)) match {
        case scala.util.Success(isEnabled) => isEnabled
        case scala.util.Failure(f) =>
          System.err.println(s"Error happened when converting '$v' to boolean.")
          false
      }
    }

    bool(sys.env.getOrElse("RUN_COMMUNITY_BUILD", "false")) ||
    bool(sys.props.getOrElse("run.community.build", "false"))
  }

  @Test
  def compileProject: Unit = {
    if (!isCommunityBuildEnabled) () else compileProject0
  }

  def compileProject0: Unit = {
    val state0 = TestUtil.loadTestProject(testDirectory, integrationTestName)
    val (initialState, projectToCompile) = getModuleToCompile(testDirectory) match {
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
          classpathOptions = ClasspathOptionsUtil.boot(),
          classesDir = classesDir,
          scalacOptions = Array.empty,
          javacOptions = Array.empty,
          sources = Array.empty,
          testFrameworks = Array.empty,
          testOptions = Config.TestOptions.empty,
          javaEnv = javaEnv,
          platform = Config.Platform.default,
          out = classesDir
        )
        val state =
          state0.copy(build = state0.build.copy(projects = rootProject :: previousProjects))

        (state, rootProject)
    }

    val reachable =
      Dag.dfs(initialState.build.getDagFor(projectToCompile)).filter(_ != projectToCompile)
    val cleanAction = Run(Commands.Clean(reachable.map(_.name)), Exit(ExitStatus.Ok))
    val state = TestUtil.blockingExecute(cleanAction, initialState)

    reachable.foreach(removeClassFiles)
    reachable.foreach { p =>
      assertTrue(s"Project `$integrationTestName/${p.name}` is already compiled.",
                 TestUtil.noPreviousResult(p, state))
    }

    val action =
      Run(Commands.Compile(projectToCompile.name, incremental = true), Exit(ExitStatus.Ok))
    val state1 = TestUtil.blockingExecute(action, state)
    reachable.foreach { p =>
      assertTrue(s"Project `$integrationTestName/${p.name}` has not been compiled.",
                 TestUtil.hasPreviousResult(p, state1))
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
