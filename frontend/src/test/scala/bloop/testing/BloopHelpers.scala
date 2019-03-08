package bloop.testing

import bloop.Compiler
import bloop.data.Project
import bloop.io.{AbsolutePath, RelativePath, ParallelOps}
import bloop.io.ParallelOps.CopyMode
import bloop.cli.Commands
import bloop.logging.{RecordingLogger, Logger}
import bloop.util.{TestProject, TestUtil}
import bloop.engine.caches.LastSuccessfulResult
import bloop.engine.{State, Run, ExecutionContext, BuildLoader}
import bloop.config.{Config, ConfigEncoderDecoders}

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.CancelableFuture

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets

trait BloopHelpers {
  def loadState(
      workspace: AbsolutePath,
      projects: List[TestProject],
      logger: RecordingLogger
  ): TestState = {
    val configDir = TestProject.populateWorkspace(workspace, projects)
    new TestState(TestUtil.loadTestProject(configDir.underlying, logger))
  }

  case class TestBuild(state: TestState, projects: List[TestProject]) {
    def projectFor(name: String): TestProject =
      projects.find(_.config.name == name).get
    def configFileFor(project: TestProject): AbsolutePath = {
      state.build.getProjectFor(project.config.name).get.origin.path
    }
  }

  def populateWorkspace(build: TestBuild, projects: List[TestProject]): AbsolutePath = {
    val configDir = build.state.build.origin
    TestProject.populateWorkspaceInConfigDir(configDir, projects)
  }

  def loadBuildFromResources(
      buildName: String,
      workspace: AbsolutePath,
      logger: RecordingLogger
  ): TestBuild = {
    val sourceConfigDir = TestUtil.getBloopConfigDir(buildName)
    val baseDir = sourceConfigDir.getParent
    val relativeConfigDir = RelativePath(sourceConfigDir.getFileName)

    val config = ParallelOps.CopyConfiguration(5, CopyMode.ReplaceExisting, Set.empty)
    val copyToNewWorkspace = ParallelOps.copyDirectories(config)(
      baseDir,
      workspace.underlying,
      ExecutionContext.ioScheduler,
      logger
    )

    val loadFromNewWorkspace = copyToNewWorkspace.flatMap { _ =>
      val configDir = relativeConfigDir.toAbsolute(workspace).underlying
      val state = new TestState(TestUtil.loadTestProject(configDir, logger, false))
      // Read project configuration files from the configuration directory
      val files = BuildLoader.readConfigurationFilesInBase(AbsolutePath(configDir), logger)
      val all = files.map(f => Task(loadTestProjectFromDisk(f.path.underlying, logger)))
      val loaders = all.grouped(5).map(group => Task.gatherUnordered(group)).toList
      Task.sequence(loaders).executeOn(ExecutionContext.ioScheduler).map { projects =>
        TestBuild(state, projects.flatten)
      }
    }

    TestUtil.await(FiniteDuration(5, "s")) {
      loadFromNewWorkspace
    }
  }

  private def loadTestProjectFromDisk(configFile: Path, logger: Logger): TestProject = {
    import _root_.io.circe.parser
    val bytes = Files.readAllBytes(configFile)
    val contents = new String(bytes, StandardCharsets.UTF_8)
    parser.parse(contents) match {
      case Left(failure) => throw failure
      case Right(json) =>
        ConfigEncoderDecoders.allDecoder.decodeJson(json) match {
          case Right(file) => TestProject(file.project, None)
          case Left(failure) => throw failure
        }
    }
  }

  final class TestState(val state: State) {
    def status = state.status
    def build = state.build
    def client = state.client
    def results = state.results
    override def toString: String = pprint.apply(state, height = 500).render

    def compileTask(project: TestProject): Task[TestState] = {
      val compileTask = Run(Commands.Compile(List(project.config.name)))
      TestUtil.interpreterTask(compileTask, state).map(new TestState(_))
    }

    def compile(projects: TestProject*): TestState = {
      val compileTask = Run(Commands.Compile(projects.map(_.config.name).toList))
      new TestState(TestUtil.blockingExecute(compileTask, state))
    }

    def compileHandle(
        project: TestProject,
        delay: Option[FiniteDuration] = None
    ): CancelableFuture[TestState] = {
      val interpretedTask = {
        val task = compileTask(project)
        delay match {
          case Some(duration) => task.delayExecution(duration)
          case None => task
        }
      }

      interpretedTask.runAsync(ExecutionContext.scheduler)
    }

    def cascadeCompile(projects: TestProject*): TestState = {
      val compileTask = Run(Commands.Compile(projects.map(_.config.name).toList, cascade = true))
      new TestState(TestUtil.blockingExecute(compileTask, state))
    }

    def clean(projects: TestProject*): TestState = {
      val compileTask = Run(Commands.Clean(projects.map(_.config.name).toList))
      new TestState(TestUtil.blockingExecute(compileTask, state))
    }

    def broadcastGlobally(): Unit = {
      State.stateCache.updateBuild(state)
      ()
    }

    def getProjectFor(project: TestProject): Project =
      build.getProjectFor(project.config.name).get

    def getLastSuccessfulResultFor(project: TestProject): Option[LastSuccessfulResult] = {
      // To access the last successful result safely, we need to wait for background tasks to finish
      state.results.lastSuccessfulResult(getProjectFor(project)).map { lastSuccessful =>
        val _ = TestUtil.await(Duration.Inf)(Task.fromFuture(lastSuccessful.populatingProducts))
        lastSuccessful
      }
    }

    def getLastResultFor(project: TestProject): Compiler.Result =
      state.results.latestResult(getProjectFor(project))

    def getLastClassesDir(project: TestProject): Option[AbsolutePath] = {
      getLastSuccessfulResultFor(project).map(_.classesDir)
    }

    def withLogger(logger: Logger): TestState =
      new TestState(state.copy(logger = logger))
  }
}
