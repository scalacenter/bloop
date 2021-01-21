package bloop.dap

import bloop.cli.ExitStatus
import bloop.data.{JdkConfig, Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.testing.{LoggingEventHandler, TestInternals}
import ch.epfl.scala.bsp.ScalaMainClass
import ch.epfl.scala.debugadapter.{CancelableFuture, DebuggeeRunner, DebuggeeListener}
import monix.eval.Task
import monix.execution.Scheduler
import xsbti.compile.analysis.SourceInfo

import java.nio.file.Path

abstract class BloopDebuggeeRunner(initialState: State, ioScheduler: Scheduler)
    extends DebuggeeRunner {
  private lazy val allAnalysis = initialState.results.allAnalysis

  override def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val debugSessionLogger = new DebuggeeLogger(listener, initialState.logger)

    val task = start(initialState.copy(logger = debugSessionLogger))
      .map { status =>
        if (!status.isOk) throw new Exception(s"debugee failed with ${status.name}")
      }
    DapCancellableFuture.runAsync(task, ioScheduler)
  }

  protected def start(state: State): Task[ExitStatus]

  override def classFilesMappedTo(
      origin: Path,
      lines: Array[Int],
      columns: Array[Int]
  ): List[Path] = {
    def isInfoEmpty(info: SourceInfo) = info == sbt.internal.inc.SourceInfos.emptyInfo

    val originFile = origin.toFile
    val foundClassFiles = allAnalysis.collectFirst {
      case analysis if !isInfoEmpty(analysis.infos.get(originFile)) =>
        analysis.relations.products(originFile).iterator.map(_.toPath).toList
    }

    foundClassFiles.toList.flatten
  }
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    env: JdkConfig,
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggeeRunner(initialState, ioScheduler) {
  def name: String = s"${getClass.getSimpleName}(${project.name}, ${mainClass.`class`})"
  def start(state: State): Task[ExitStatus] = {
    val workingDir = state.commonOptions.workingPath
    // TODO: https://github.com/scalacenter/bloop/issues/1456
    // Metals used to add the `-J` prefix but it is not needed anymore
    // So we cautiously strip it off
    val jvmOptions = mainClass.jvmOptions.map(_.stripPrefix("-J"))
    val runState = Tasks.runJVM(
      state,
      project,
      env,
      workingDir,
      mainClass.`class`,
      mainClass.arguments.toArray,
      jvmOptions.toArray,
      mainClass.environmentVariables,
      RunMode.Debug
    )
    runState.map(_.status)
  }
}

private final class TestSuiteDebugAdapter(
    projects: Seq[Project],
    filters: List[String],
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggeeRunner(initialState, ioScheduler) {
  override def name: String = {
    val projectsStr = projects.map(_.bspUri).mkString("[", ", ", "]")
    val filtersStr = filters.mkString("[", ", ", "]")
    s"${getClass.getSimpleName}($projectsStr, $filtersStr)"
  }
  override def start(state: State): Task[ExitStatus] = {
    val filter = TestInternals.parseFilters(filters)
    val handler = new LoggingEventHandler(state.logger)

    val task = Tasks.test(
      state,
      projects.toList,
      Nil,
      filter,
      handler,
      mode = RunMode.Debug
    )

    task.map(_.status)
  }
}

private final class AttachRemoteDebugAdapter(initialState: State, ioScheduler: Scheduler)
    extends BloopDebuggeeRunner(initialState, ioScheduler) {
  override def name: String = s"${getClass.getSimpleName}(${initialState.build.origin})"
  override def start(state: State): Task[ExitStatus] = Task(ExitStatus.Ok)
}

object BloopDebuggeeRunner {
  def forMainClass(
      projects: Seq[Project],
      mainClass: ScalaMainClass,
      state: State,
      ioScheduler: Scheduler
  ): Either[String, DebuggeeRunner] = {
    projects match {
      case Seq() => Left(s"No projects specified for main class: [$mainClass]")
      case Seq(project) =>
        project.platform match {
          case jvm: Platform.Jvm =>
            Right(new MainClassDebugAdapter(project, mainClass, jvm.config, state, ioScheduler))
          case platform =>
            Left(s"Unsupported platform: ${platform.getClass.getSimpleName}")
        }

      case projects => Left(s"Multiple projects specified for main class [$mainClass]: $projects")
    }
  }

  def forTestSuite(
      projects: Seq[Project],
      filters: List[String],
      state: State,
      ioScheduler: Scheduler
  ): Either[String, DebuggeeRunner] = {
    projects match {
      case Seq() => Left(s"No projects specified for the test suites: [${filters.sorted}]")
      case projects => Right(new TestSuiteDebugAdapter(projects, filters, state, ioScheduler))
    }
  }

  def forAttachRemote(state: State, ioScheduler: Scheduler): DebuggeeRunner =
    new AttachRemoteDebugAdapter(state, ioScheduler)
}
