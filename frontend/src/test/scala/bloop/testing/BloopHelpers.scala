package bloop.testing

import bloop.Compiler
import bloop.data.Project
import bloop.io.{AbsolutePath, RelativePath, ParallelOps}
import bloop.io.ParallelOps.CopyMode
import bloop.cli.Commands
import bloop.logging.{RecordingLogger, Logger}
import bloop.util.{TestProject, TestUtil}
import bloop.engine.caches.LastSuccessfulResult
import bloop.engine.{State, Run, ExecutionContext, BuildLoader, Dag}
import bloop.config.{Config, ConfigEncoderDecoders}

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.CancelableFuture

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets
import bloop.data.WorkspaceSettings

trait BloopHelpers {
  self: BaseSuite =>
  def loadState(
      workspace: AbsolutePath,
      projects: List[TestProject],
      logger: RecordingLogger,
      settings: Option[WorkspaceSettings] = None
  ): TestState = {
    val configDir = TestProject.populateWorkspace(workspace, projects)
    settings.foreach(WorkspaceSettings.write(configDir, _))
    new TestState(TestUtil.loadTestProject(configDir.underlying, logger))
  }

  case class TestBuild(state: TestState, projects: List[TestProject]) {
    def projectFor(name: String): TestProject = {
      projects.find(_.config.name == name).get
    }
    def configFileFor(project: TestProject): AbsolutePath = {
      state.build.getProjectFor(project.config.name).get.origin.path
    }
    def withLogger(logger: Logger): TestBuild = {
      TestBuild(state = state.withLogger(logger), projects)
    }
  }

  def populateWorkspace(build: TestBuild, projects: List[TestProject]): AbsolutePath = {
    val configDir = build.state.build.origin
    TestProject.populateWorkspaceInConfigDir(configDir, projects)
  }

  def reloadWithNewProject(project: TestProject, state: TestState): TestState = {
    val buildProject = state.getProjectFor(project)
    val configFile = buildProject.origin.path
    val newConfigJson = project.toJson
    writeFile(configFile, newConfigJson)
    val configDir = state.build.origin
    new TestState(TestUtil.loadTestProject(configDir.underlying, state.state.logger))
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
      logger,
      enableCancellation = false
    )

    val loadFromNewWorkspace = copyToNewWorkspace.flatMap { _ =>
      val configDir = relativeConfigDir.toAbsolute(workspace).underlying
      // Read project configuration files from the configuration directory
      val files = BuildLoader.readConfigurationFilesInBase(AbsolutePath(configDir), logger)
      val all = files.map { f =>
        Task {
          val configFile = f.path.underlying
          val oldWorkspace = AbsolutePath(baseDir)
          loadTestProjectFromDisk(configFile, oldWorkspace.syntax, workspace.syntax, logger)
        }
      }

      val loaders = all.grouped(5).map(group => Task.gatherUnordered(group)).toList
      Task.sequence(loaders).executeOn(ExecutionContext.ioScheduler).map { projects =>
        val state = new TestState(TestUtil.loadTestProject(configDir, logger, false))
        TestBuild(state, projects.flatten)
      }
    }

    TestUtil.await(FiniteDuration(15, "s"), ExecutionContext.ioScheduler) {
      loadFromNewWorkspace
    }
  }

  private def loadTestProjectFromDisk(
      configFile: Path,
      previousBaseDir: String,
      newBaseDir: String,
      logger: Logger
  ): TestProject = {
    import _root_.io.circe.parser
    val bytes = Files.readAllBytes(configFile)
    val contents = new String(bytes, StandardCharsets.UTF_8)
    val newContents = contents.replace(previousBaseDir, newBaseDir)
    import java.nio.file.StandardOpenOption
    Files.write(
      configFile,
      newContents.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.SYNC,
      StandardOpenOption.WRITE
    )

    parser.parse(newContents) match {
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

    def compileTask(project: TestProject, watch: Boolean = false): Task[TestState] = {
      val compileTask = Run(Commands.Compile(List(project.config.name), watch = watch))
      TestUtil.interpreterTask(compileTask, state).map(new TestState(_))
    }

    def compile(projects: TestProject*): TestState = {
      val compileTask = Run(Commands.Compile(projects.map(_.config.name).toList))
      new TestState(TestUtil.blockingExecute(compileTask, state))
    }

    def compileWithPipelining(projects: TestProject*): TestState = {
      val projectNames = projects.map(_.config.name).toList
      val compileTask = Run(Commands.Compile(projectNames, pipeline = true))
      new TestState(TestUtil.blockingExecute(compileTask, state))
    }

    def console(projects: TestProject*): TestState = {
      val compileTask = Run(Commands.Console(projects.map(_.config.name).toList))
      new TestState(TestUtil.blockingExecute(compileTask, state))
    }

    def compileHandle(
        project: TestProject,
        delay: Option[FiniteDuration] = None,
        watch: Boolean = false,
        beforeTask: Task[TestState] = Task.now(this)
    ): CancelableFuture[TestState] = {
      val interpretedTask = {
        val task0 = beforeTask.flatMap { newState =>
          newState.compileTask(project, watch)
        }

        delay match {
          case Some(duration) => task0.delayExecution(duration)
          case None => task0
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

    def testTask(project: TestProject, only: List[String], args: List[String]): Task[TestState] = {
      val testTask = Run(Commands.Test(List(project.config.name), only = only, args = args))
      TestUtil.interpreterTask(testTask, state).map(new TestState(_))
    }

    def test(project: TestProject, only: List[String], args: List[String]): TestState = {
      TestUtil.await(FiniteDuration(20, "s")) {
        testTask(project, only, args)
      }
    }

    def test(project: TestProject): TestState = {
      test(project, Nil, Nil)
    }

    def testHandle(
        project: TestProject,
        only: List[String],
        args: List[String],
        delay: Option[FiniteDuration]
    ): CancelableFuture[TestState] = {
      val interpretedTask = {
        val task = testTask(project, only, args)
        delay match {
          case Some(duration) => task.delayExecution(duration)
          case None => task
        }
      }

      interpretedTask.runAsync(ExecutionContext.scheduler)
    }

    def getProjectFor(project: TestProject): Project =
      build.getProjectFor(project.config.name).get

    def getDagFor(project: TestProject): Dag[Project] =
      build.getDagFor(getProjectFor(project))

    def getLastSuccessfulResultFor(project: TestProject): Option[LastSuccessfulResult] = {
      // To access the last successful result safely, we need to wait for background tasks to finish
      state.results.lastSuccessfulResult(getProjectFor(project)).map { lastSuccessful =>
        val _ = TestUtil.await(Duration.Inf)(lastSuccessful.populatingProducts)
        lastSuccessful
      }
    }

    def getLastResultFor(project: TestProject): Compiler.Result =
      state.results.latestResult(getProjectFor(project))

    def getLastClassesDir(project: TestProject): Option[AbsolutePath] = {
      getLastSuccessfulResultFor(project).map(_.classesDir)
    }

    def getClientExternalDir(project: TestProject): AbsolutePath = {
      val buildProject = getProjectFor(project)
      client.getUniqueClassesDirFor(buildProject)
    }

    def getLatestSavedStateGlobally(): TestState = {
      val globalMutableState = State.stateCache.getStateFor(
        build.origin,
        client,
        state.pool,
        state.commonOptions,
        state.logger
      )

      globalMutableState.map(s => new TestState(s)).getOrElse(this)
    }

    def withLogger(logger: Logger): TestState =
      new TestState(state.copy(logger = logger))

    def withNewCompilerCache: TestState =
      new TestState(state.copy(compilerCache = state.compilerCache.duplicateWith(state.logger)))

    def backup: TestState = {
      import java.nio.file.Files
      val logger = this.state.logger
      val newSuccessfulTasks = state.results.successful.map {
        case (project, result) =>
          result.populatingProducts.flatMap { _ =>
            val classesDir = result.classesDir.underlying
            val newClassesDir = {
              val newClassesDirName = s"${classesDir.getFileName}-backup"
              Files.createDirectories(classesDir.getParent.resolve(newClassesDirName))
            }

            val backupDir = ParallelOps.copyDirectories(
              ParallelOps.CopyConfiguration(2, ParallelOps.CopyMode.ReplaceExisting, Set.empty)
            )(
              classesDir,
              newClassesDir,
              ExecutionContext.ioScheduler,
              logger,
              enableCancellation = false
            )

            backupDir.map { _ =>
              val newResult = result.copy(classesDir = AbsolutePath(newClassesDir))
              project -> newResult
            }
          }
      }

      TestUtil.await(scala.concurrent.duration.FiniteDuration(5, "s")) {
        Task.gatherUnordered(newSuccessfulTasks).map {
          case newSuccessful =>
            val newResults = state.results.copy(successful = newSuccessful.toMap)
            new TestState(state.copy(results = newResults))
        }
      }
    }

  }
}
