package bloop.dap

import bloop.cli.ExitStatus
import bloop.data.{Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.data.JdkConfig
import bloop.testing.{LoggingEventHandler, TestInternals}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task
import java.nio.file.Path
import xsbti.compile.analysis.SourceInfo
import sbt.internal.inc.Analysis
import bloop.logging.Logger

abstract class DebuggeeRunner {
  def logger: Logger
  def run(logger: DebugSessionLogger): Task[ExitStatus]
  def classFilesMappedTo(origin: Path, lines: Array[Int], columns: Array[Int]): List[Path]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    env: JdkConfig,
    state: State
) extends DebuggeeRunner {
  private lazy val allAnalysis = state.results.allAnalysis
  def classFilesMappedTo(
      origin: Path,
      lines: Array[Int],
      columns: Array[Int]
  ): List[Path] = {
    DebuggeeRunner.classFilesMappedTo(origin, lines, columns, allAnalysis)
  }

  def logger: Logger = {
    state.logger
  }

  def run(debugLogger: DebugSessionLogger): Task[ExitStatus] = {
    val workingDir = state.commonOptions.workingPath
    val runState = Tasks.runJVM(
      state.copy(logger = debugLogger),
      project,
      env,
      workingDir,
      mainClass.`class`,
      (mainClass.arguments ++ mainClass.jvmOptions).toArray,
      skipJargs = false,
      mainClass.environmentVariables,
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
  private lazy val allAnalysis = state.results.allAnalysis
  def classFilesMappedTo(
      origin: Path,
      lines: Array[Int],
      columns: Array[Int]
  ): List[Path] = {
    DebuggeeRunner.classFilesMappedTo(origin, lines, columns, allAnalysis)
  }

  def logger: Logger = {
    state.logger
  }

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
      runInParallel = false,
      mode = RunMode.Debug
    )

    task.map(_.status)
  }
}

private final class AttachRemoteDebugAdapter(state: State) extends DebuggeeRunner {
  private lazy val allAnalysis = state.results.allAnalysis
  override def logger: Logger = state.logger

  override def run(logger: DebugSessionLogger): Task[ExitStatus] = Task(ExitStatus.Ok)

  override def classFilesMappedTo(
      origin: Path,
      lines: Array[Int],
      columns: Array[Int]
  ): List[Path] = {
    DebuggeeRunner.classFilesMappedTo(origin, lines, columns, allAnalysis)
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

  def forAttachRemote(state: State): DebuggeeRunner =
    new AttachRemoteDebugAdapter(state)

  def classFilesMappedTo(
      origin: Path,
      lines: Array[Int],
      columns: Array[Int],
      allAnalysis: Seq[Analysis]
  ): List[Path] = {
    def isInfoEmpty(info: SourceInfo) = info == sbt.internal.inc.SourceInfos.emptyInfo

    val originFile = origin.toFile
    val foundClassFiles = allAnalysis.collectFirst { analysis =>
      analysis match {
        case analysis if !isInfoEmpty(analysis.infos.get(originFile)) =>
          analysis.relations.products(originFile).iterator.map(_.toPath).toList
      }
    }

    foundClassFiles.toList.flatten
  }
}
