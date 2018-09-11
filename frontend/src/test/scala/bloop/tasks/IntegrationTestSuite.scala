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
import bloop.engine.{Dag, Exit, Run}
import bloop.exec.JavaEnv
import bloop.Project
import bloop.config.Config
import bloop.io.AbsolutePath

object IntegrationTestSuite {
  val projects = TestUtil.testProjectsIndex.map(_._2).toArray.map(Array.apply(_))

  @Parameters
  def data() = {
    //Arrays.asList(Array(Array((java.nio.file.Paths.get("/Users/jvican/Code/Atlas/.bloop"))): _*))

    Arrays.asList(projects: _*)
  }
}

@Category(Array(classOf[bloop.SlowTests]))
@RunWith(classOf[Parameterized])
class IntegrationTestSuite(testDirectory: Path) {
  val integrationTestName = TestUtil.getBaseFromConfigDir(testDirectory).getFileName.toString

  val isCommunityBuildEnabled: Boolean =
    isEnvironmentEnabled(List("RUN_COMMUNITY_BUILD", "run.community.build"), "false")
  val isPipeliningEnabled: Boolean =
    isEnvironmentEnabled(List("PIPELINE_COMMUNITY_BUILD", "pipeline.community.build"), "false")

  private def isEnvironmentEnabled(keys: List[String], default: String): Boolean = {
    import scala.util.Try
    def bool(v: String): Boolean = {
      Try(java.lang.Boolean.parseBoolean(v)) match {
        case scala.util.Success(isEnabled) => isEnabled
        case scala.util.Failure(f) =>
          System.err.println(s"Error happened when converting '$v' to boolean.")
          false
      }
    }

    keys.exists(k => bool(sys.env.getOrElse(k, default)))
  }

  @Test
  def compileProject: Unit = {
    if (!isCommunityBuildEnabled)
      println(s"Skipping ${testDirectory} (community build is disabled)")
    else {
      if (isPipeliningEnabled) println(s"*** PIPELINING COMPILATION FOR ${testDirectory} ***")
      else println(s"*** NORMAL COMPILATION FOR ${testDirectory} ***")
      // After reporting the state of the execution, compile the projects accordingly.
      compileProject0
    }
  }

  def compileProject0: Unit = {
    val state0 = TestUtil.loadTestProject(testDirectory, integrationTestName, identity)
    val (initialState, projectToCompile) = getModuleToCompile(testDirectory) match {
      case Some(projectName) =>
        (state0, state0.build.getProjectFor(projectName).get)

      case None =>
        val rootProjectName = "bloop-test-root"
        val classesDir = AbsolutePath(testDirectory)
        val previousProjects = state0.build.projects
        val javaEnv = JavaEnv.default

        val rootProject = Project(
          name = rootProjectName,
          baseDirectory = AbsolutePath(testDirectory),
          dependencies = previousProjects.map(_.name),
          scalaInstance = previousProjects.head.scalaInstance,
          rawClasspath = Nil,
          compileSetup = Config.CompileSetup.empty,
          classesDir = classesDir,
          scalacOptions = Nil,
          javacOptions = Nil,
          sources = Nil,
          testFrameworks = Nil,
          testOptions = Config.TestOptions.empty,
          javaEnv = javaEnv,
          out = classesDir,
          analysisOut = classesDir.resolve(Config.Project.analysisFileName(rootProjectName)),
          platform = Config.Platform.default,
          jsToolchain = None,
          nativeToolchain = None,
          sbt = None,
          resolution = None
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

    val enablePipelining = isPipeliningEnabled
    val action = Run(
      Commands.Compile(
        projectToCompile.name,
        incremental = true,
        pipelined = isPipeliningEnabled
      ),
      Exit(ExitStatus.Ok)
    )

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
