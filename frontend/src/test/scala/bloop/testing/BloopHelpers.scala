package bloop.testing

import bloop.Compiler
import bloop.data.Project
import bloop.io.AbsolutePath
import bloop.cli.Commands
import bloop.logging.{RecordingLogger, Logger}
import bloop.util.{TestProject, TestUtil}
import bloop.engine.caches.LastSuccessfulResult
import bloop.engine.{State, Run, ExecutionContext}

import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import monix.eval.Task
import monix.execution.CancelableFuture

trait BloopHelpers {
  def loadState(
      workspace: AbsolutePath,
      projects: List[TestProject],
      logger: RecordingLogger
  ): TestState = {
    val configDir = TestProject.populateWorkspace(workspace, projects)
    new TestState(TestUtil.loadTestProject(configDir.underlying, identity(_)).copy(logger = logger))
  }

  final class TestState(val state: State) {
    def status = state.status
    def build = state.build
    def client = state.client
    def results = state.results
    override def toString: String = pprint.apply(state, height = 500).render
    def compile(projects: TestProject*): TestState = {
      val compileTask = Run(Commands.Compile(projects.map(_.config.name).toList))
      new TestState(TestUtil.blockingExecute(compileTask, state))
    }

    def compileHandle(
        project: TestProject,
        delay: Option[FiniteDuration] = None
    ): CancelableFuture[TestState] = {
      val interpretedTask = {
        val compileTask = Run(Commands.Compile(List(project.config.name)))
        val task = TestUtil.interpreterTask(compileTask, state)
        delay match {
          case Some(duration) => task.delayExecution(duration)
          case None => task
        }
      }

      interpretedTask
        .runAsync(ExecutionContext.scheduler)
        .map(state => new TestState(state))(ExecutionContext.scheduler)
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
