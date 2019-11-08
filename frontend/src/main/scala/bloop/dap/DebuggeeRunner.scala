package bloop.dap

import bloop.cli.ExitStatus
import bloop.data.{Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.data.JdkConfig
import bloop.testing.{LoggingEventHandler, TestInternals}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task

trait DebuggeeRunner {
  def run(logger: DebugSessionLogger): Task[ExitStatus]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    env: JdkConfig,
    state: State
) extends DebuggeeRunner {
  def run(debugLogger: DebugSessionLogger): Task[ExitStatus] = {
    val workingDir = state.commonOptions.workingPath
    val runState = Tasks.runJVM(
      state.copy(logger = debugLogger),
      project,
      env,
      workingDir,
      mainClass.`class`,
      mainClass.arguments.toArray,
      skipJargs = false,
      RunMode.Debug
    )

    runState.map(_.status)
  }
}

private final class TestSuiteDebugAdapter(
    projects: Seq[Project],
    filters: List[String],
    state: State
) extends DebuggeeRunner {
  def run(debugLogger: DebugSessionLogger): Task[ExitStatus] = {
    val debugState = state.copy(logger = debugLogger)

    val filter = TestInternals.parseFilters(filters)
    val handler = new LoggingEventHandler(debugState.logger)

    val task = Tasks.test(
      debugState,
      projects.toList,
      Nil,
      filter,
      handler,
      failIfNoTestFrameworks = true,
      runInParallel = false,
      mode = RunMode.Debug
    )

    task.map(_.status)
  }
}

object DebuggeeRunner {
  def forMainClass(
      projects: Seq[Project],
      mainClass: ScalaMainClass,
      state: State
  ): Either[String, DebuggeeRunner] = {
    projects match {
      case Seq() => Left(s"No projects specified for main class: [$mainClass]")
      case Seq(project) =>
        project.platform match {
          case jvm: Platform.Jvm =>
            Right(new MainClassDebugAdapter(project, mainClass, jvm.config, state))
          case platform =>
            Left(s"Unsupported platform: ${platform.getClass.getSimpleName}")
        }

      case projects => Left(s"Multiple projects specified for main class [$mainClass]: $projects")
    }
  }

  def forTestSuite(
      projects: Seq[Project],
      filters: List[String],
      state: State
  ): Either[String, DebuggeeRunner] = {
    projects match {
      case Seq() => Left(s"No projects specified for the test suites: [${filters.sorted}]")
      case projects => Right(new TestSuiteDebugAdapter(projects, filters, state))
    }
  }
}
