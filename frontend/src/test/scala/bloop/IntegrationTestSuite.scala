package bloop

import java.nio.file.{Files, Path}
import java.util.Arrays

import bloop.cli.{Commands, ExitStatus}
import bloop.config.Config
import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.util.TestUtil
import bloop.engine.{Dag, Exit, Run}
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

object IntegrationTestSuite {
  @Parameters
  def data() = {
    import java.nio.file.Paths
    def filterIndex(index: Map[String, Path]): Map[String, Path] =
      index //.filterKeys(_.contains("lichess"))
    val projects = filterIndex(TestUtil.testProjectsIndex).map(_._2).toArray.map(Array.apply(_))
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
  val runCommunityBuildExtraIterations: Boolean =
    isEnvironmentEnabled(
      List("RUN_ITERATIONS_COMMUNITY_BUILD", "run.community.build.iteration"),
      "false"
    )

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
  def compileProject(): Unit = {
    if (!isCommunityBuildEnabled)
      println(s"Skipping ${testDirectory} (community build is disabled)")
    else {
      if (isPipeliningEnabled) println(s"*** PIPELINING COMPILATION FOR ${testDirectory} ***")
      else println(s"*** NORMAL COMPILATION FOR ${testDirectory} ***")
      // After reporting the state of the execution, compile the projects accordingly.
      compileProject0

      if (runCommunityBuildExtraIterations) {
        println(s"*** COMPILE ${testDirectory} FOR THE SECOND TIME ***")
        compileProject0

        println(s"*** COMPILE ${testDirectory} FOR THE THIRD TIME ***")
        compileProject0
      }
    }
  }

  def compileProject0: Unit = {
    val state0 = TestUtil.loadTestProject(testDirectory)
    val (initialState, projectToCompile) = getModuleToCompile(testDirectory) match {
      case Some(projectName) =>
        (state0, state0.build.getProjectFor(projectName).get)

      case None =>
        val rootProjectName = "bloop-test-root"
        val classesDir = AbsolutePath(testDirectory)
        val previousProjects = state0.build.projects

        val rootProject = Project(
          name = rootProjectName,
          baseDirectory = AbsolutePath(testDirectory),
          dependencies = previousProjects.map(_.name),
          scalaInstance = previousProjects.head.scalaInstance,
          rawClasspath = Nil,
          resources = Nil,
          compileSetup = Config.CompileSetup.empty,
          genericClassesDir = classesDir,
          scalacOptions = Nil,
          javacOptions = Nil,
          sources = Nil,
          testFrameworks = Nil,
          testOptions = Config.TestOptions.empty,
          out = classesDir,
          analysisOut = classesDir.resolve(Config.Project.analysisFileName(rootProjectName)),
          platform = Project.defaultPlatform(state0.logger),
          sbt = None,
          resolution = None,
          origin = TestUtil.syntheticOriginFor(state0.build.origin)
        )
        val state =
          state0.copy(build = state0.build.copy(projects = rootProject :: previousProjects))

        //val rootProject1 = state0.build.getProjectFor("frontend-test").get
        (state, rootProject)
    }

    val allReachable = Dag.dfs(initialState.build.getDagFor(projectToCompile))
    val reachable = allReachable.filter(_ != projectToCompile)
    val cleanAction = Run(Commands.Clean(reachable.map(_.name)), Exit(ExitStatus.Ok))
    val state = TestUtil.blockingExecute(cleanAction, initialState)

    allReachable.foreach(removeClassFiles)
    reachable.foreach { p =>
      assertTrue(
        s"Project `$integrationTestName/${p.name}` is already compiled.",
        TestUtil.noPreviousAnalysis(p, state)
      )
    }

    import bloop.cli.CliOptions
    val enablePipelining = isPipeliningEnabled
    val action = Run(
      Commands.Compile(
        List(projectToCompile.name),
        incremental = true,
        pipeline = isPipeliningEnabled
        //cliOptions = CliOptions.default.copy(verbose = true)
      ),
      Exit(ExitStatus.Ok)
    )

    val verboseState = state //.copy(logger = state.logger.asVerbose)
    val state1 = TestUtil.blockingExecute(action, verboseState)
    assert(state1.status.isOk)
    reachable.foreach { p =>
      assertTrue(
        s"Project `$integrationTestName/${p.name}` has not been compiled.",
        TestUtil.hasPreviousResult(p, state1)
      )
    }

    println("Triggering extra no-op compile")
    val state2 = TestUtil.blockingExecute(action, state1)
  }

  private def removeClassFiles(p: Project): Unit = {
    val classesDirPath = p.genericClassesDir.underlying
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
