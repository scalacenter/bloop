package bloop

import bloop.io.AbsolutePath
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import bloop.logging.Logger
import bloop.engine.BuildLoader
import bloop.engine.Build
import bloop.engine.State
import sbt.internal.inc.bloop.ZincInternals
import bloop.logging.NoopLogger
import bloop.engine.caches.ResultsCache
import bloop.logging.BloopLogger
import bloop.data.Project
import bloop.config.Config
import bloop.data.Origin
import java.nio.file.attribute.FileTime
import bloop.engine.Dag
import bloop.engine.Exit
import bloop.cli.ExitStatus
import bloop.engine.Run
import bloop.cli.Commands
import bloop.engine.Action
import scala.concurrent.duration.Duration
import bloop.engine.ExecutionContext
import bloop.engine.Interpreter
import monix.eval.Task
import scala.concurrent.Await
import monix.execution.misc.NonFatal

object CommunityBuild
    extends CommunityBuild(
      AbsolutePath(System.getProperty("user.home")).resolve(".buildpress")
    ) {
  def main(args: Array[String]): Unit = {
    if (builds.isEmpty) {
      System.err.println(s"❌  No builds were found in buildpress home $buildpressHomeDir")
    } else {
      builds.foreach {
        case (buildName, buildBaseDir) =>
          compileProject(buildBaseDir)
          System.out.println(s"✅  Compiled all projects in $buildBaseDir")
      }
    }
  }
}

class CommunityBuild(val buildpressHomeDir: AbsolutePath) {
  val buildpressCacheDir = buildpressHomeDir.resolve("cache")
  val buildpressCacheFile = buildpressHomeDir.resolve("buildpress.out")
  def exit(exitCode: Int): Unit = System.exit(exitCode)

  lazy val builds: Map[String, AbsolutePath] = {
    if (!buildpressCacheFile.exists) Map.empty
    else {
      val bytes = Files.readAllBytes(buildpressCacheFile.underlying)
      val lines = new String(bytes, StandardCharsets.UTF_8).split(System.lineSeparator())
      lines.toList.zipWithIndex.flatMap {
        case (line, idx) =>
          if (line.startsWith("//")) Nil
          else {
            val lineNumber = idx + 1
            def position = s"$buildpressCacheFile:$lineNumber"
            line.split(",") match {
              //case Array("") => Nil
              case Array() | Array(_) =>
                sys.error(s"Missing comma between repo id and repo URI at $position")
              case Array(untrimmedRepoId, _) =>
                val repoId = untrimmedRepoId.trim
                val buildBaseDir = buildpressCacheDir.resolve(repoId)
                if (buildBaseDir.exists) List(repoId -> buildBaseDir)
                else sys.error(s"Missing ${buildBaseDir}")
              case elements =>
                sys.error(
                  s"Expected buildpress line format 'id,uri' at $position, obtained '$line'"
                )
            }
          }
      }.toMap
    }
  }

  def getConfigDirForBenchmark(name: String): Path = {
    builds.getOrElse(name, sys.error(s"Missing buildpress base dir for $name")).underlying
  }

  val compilerCache: CompilerCache = {
    import bloop.io.Paths
    val jars = Paths.getCacheDirectory("scala-jars")
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    new CompilerCache(provider, jars, NoopLogger, Nil)
  }

  def loadStateForBuild(configDirectory: AbsolutePath, logger: Logger): State = {
    assert(configDirectory.exists, "Does not exist: " + configDirectory)
    val loadedProjects = BuildLoader.loadSynchronously(configDirectory, logger)
    val build = Build(configDirectory, loadedProjects)
    val state = State.forTests(build, compilerCache, logger)
    state.copy(results = ResultsCache.emptyForTests)
  }

  def compileProject(buildBaseDir: AbsolutePath): Unit = {
    if (!isCommunityBuildEnabled)
      println(s"➡️  Skipping $buildBaseDir (community build is disabled)")
    else {
      if (isPipeliningEnabled) {
        println(s"➡️  Compiling all projects in $buildBaseDir with pipelining enabled")
      } else {
        println(s"➡️  Compiling all projects in $buildBaseDir")
      }

      // After reporting the state of the execution, compile the projects accordingly.
      val logger = BloopLogger.default("community-build-logger")
      val initialState = loadStateForBuild(buildBaseDir, logger)
      val allProjectsInBuild = initialState.build.projects

      val rootProjectName = "bloop-test-root"
      val dummyExistingBaseDir = buildBaseDir.resolve("project")
      val dummyClassesDir = dummyExistingBaseDir.resolve("target")
      val origin = Origin(buildBaseDir, FileTime.fromMillis(0), scala.util.Random.nextInt())
      val analysisOut = dummyClassesDir.resolve(Config.Project.analysisFileName(rootProjectName))
      val rootProject = Project(
        name = rootProjectName,
        baseDirectory = dummyExistingBaseDir,
        dependencies = allProjectsInBuild.map(_.name),
        scalaInstance = allProjectsInBuild.head.scalaInstance,
        rawClasspath = Nil,
        resources = Nil,
        compileSetup = Config.CompileSetup.empty,
        genericClassesDir = dummyClassesDir,
        scalacOptions = Nil,
        javacOptions = Nil,
        sources = Nil,
        testFrameworks = Nil,
        testOptions = Config.TestOptions.empty,
        out = dummyClassesDir,
        analysisOut = analysisOut,
        platform = Project.defaultPlatform(initialState.logger),
        sbt = None,
        resolution = None,
        origin = origin
      )

      val allReachable = Dag.dfs(initialState.build.getDagFor(rootProject))
      val reachable = allReachable.filter(_ != rootProject)
      val cleanAction = Run(Commands.Clean(reachable.map(_.name)), Exit(ExitStatus.Ok))
      val state = execute(cleanAction, initialState)

      reachable.foreach { project =>
        removeClassFiles(project)
        if (!noPreviousAnalysis(project, state)) {
          System.err.println(s"Project ${project.baseDirectory} already compiled!")
          exit(1)
        }
      }

      val action = Run(
        Commands.Compile(
          List(rootProjectName),
          incremental = true,
          pipeline = isPipeliningEnabled
          //cliOptions = CliOptions.default.copy(verbose = true)
        ),
        Exit(ExitStatus.Ok)
      )

      val verboseState = state //.copy(logger = state.logger.asVerbose)
      val compiledState = execute(action, verboseState)
      assert(compiledState.status.isOk)
      reachable.foreach { project =>
        if (noPreviousAnalysis(project, state)) {
          System.err.println(s"Project ${project.baseDirectory} was not compiled!")
          exit(1)
        }
      }
    }
  }

  private def execute(a: Action, state: State, duration: Duration = Duration.Inf): State = {
    val task = Interpreter.execute(a, Task.now(state))
    val handle = task.runAsync(ExecutionContext.scheduler)
    try Await.result(handle, duration)
    catch {
      case NonFatal(t) => handle.cancel(); throw t
      case i: InterruptedException => handle.cancel(); state
    }
  }

  private def removeClassFiles(p: Project): Unit = {
    val classesDirPath = p.genericClassesDir.underlying
    if (Files.exists(classesDirPath)) {
      Files.newDirectoryStream(classesDirPath, "*.class").forEach(p => Files.delete(p))
    }
  }

  private def noPreviousAnalysis(project: Project, state: State): Boolean =
    !state.results.lastSuccessfulResultOrEmpty(project).previous.analysis().isPresent

  private val isCommunityBuildEnabled: Boolean =
    isEnvironmentEnabled(List("RUN_COMMUNITY_BUILD", "run.community.build"), "false")
  private val isPipeliningEnabled: Boolean =
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
}
