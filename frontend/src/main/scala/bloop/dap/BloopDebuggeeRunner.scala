package bloop.dap

import bloop.ScalaInstance
import bloop.cli.ExitStatus
import bloop.data.{JdkConfig, Platform, Project}
import bloop.engine.{Build, State}
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.logging.Logger
import bloop.testing.{LoggingEventHandler, TestInternals}
import ch.epfl.scala.bsp.ScalaMainClass
import ch.epfl.scala.debugadapter._
import monix.eval.Task
import monix.execution.Scheduler

import java.net.URLClassLoader
import scala.collection.mutable
import scala.annotation.tailrec

abstract class BloopDebuggeeRunner(initialState: State, ioScheduler: Scheduler)
    extends DebuggeeRunner {

  override def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val debugSessionLogger = new DebuggeeLogger(listener, initialState.logger)

    val task = start(initialState.copy(logger = debugSessionLogger))
      .map { status =>
        if (!status.isOk) throw new Exception(s"debugee failed with ${status.name}")
      }
    DapCancellableFuture.runAsync(task, ioScheduler)
  }

  protected def start(state: State): Task[ExitStatus]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    val classPathEntries: Seq[ClassPathEntry],
    val evaluationClassLoader: Option[ClassLoader],
    env: JdkConfig,
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggeeRunner(initialState, ioScheduler) {
  val javaRuntime = JavaRuntime(env.javaHome.underlying)
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
    val classPathEntries: Seq[ClassPathEntry],
    val javaRuntime: Option[JavaRuntime],
    val evaluationClassLoader: Option[ClassLoader],
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

private final class AttachRemoteDebugAdapter(
    val classPathEntries: Seq[ClassPathEntry],
    val javaRuntime: Option[JavaRuntime],
    val evaluationClassLoader: Option[ClassLoader],
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggeeRunner(initialState, ioScheduler) {
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
            val classPathEntries = getClassPathEntries(state.build, project)
            val evaluationClassLoader = getEvaluationClassLoader(project, state, ioScheduler)
            Right(
              new MainClassDebugAdapter(
                project,
                mainClass,
                classPathEntries,
                evaluationClassLoader,
                jvm.config,
                state,
                ioScheduler
              )
            )
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
      case Seq(project) if project.platform.isInstanceOf[Platform.Jvm] =>
        val Platform.Jvm(config, _, _, _, _, _) = project.platform
        val classPathEntries = getClassPathEntries(state.build, project)
        val javaRuntime = JavaRuntime(config.javaHome.underlying)
        val evaluationClassLoader = getEvaluationClassLoader(project, state, ioScheduler)
        Right(
          new TestSuiteDebugAdapter(
            projects,
            filters,
            classPathEntries,
            javaRuntime,
            evaluationClassLoader,
            state,
            ioScheduler
          )
        )
      case _ =>
        Right(
          new TestSuiteDebugAdapter(projects, filters, Seq.empty, None, None, state, ioScheduler)
        )
    }
  }

  def forAttachRemote(
      state: State,
      ioScheduler: Scheduler,
      projects: Seq[Project]
  ): DebuggeeRunner = {
    projects match {
      case Seq(project) if project.platform.isInstanceOf[Platform.Jvm] =>
        val Platform.Jvm(config, _, _, _, _, _) = project.platform
        val classPathEntries = getClassPathEntries(state.build, project)
        val javaRuntime = JavaRuntime(config.javaHome.underlying)
        val evaluationClassLoader = getEvaluationClassLoader(project, state, ioScheduler)
        new AttachRemoteDebugAdapter(
          classPathEntries,
          javaRuntime,
          evaluationClassLoader,
          state,
          ioScheduler
        )
      case _ => new AttachRemoteDebugAdapter(Seq.empty, None, None, state, ioScheduler)
    }
  }

  private def getClassPathEntries(build: Build, project: Project): Seq[ClassPathEntry] = {
    val allProjects = getAllDepsRecursively(build, project)
    getLibraries(allProjects) ++ getClassDirectories(allProjects)
  }

  private def getEvaluationClassLoader(
      project: Project,
      state: State,
      ioScheduler: Scheduler
  ): Option[ClassLoader] = {
    project.scalaInstance
      .flatMap(getEvaluationClassLoader(_, state.logger, ioScheduler))
  }

  private def getEvaluationClassLoader(
      scalaInstance: ScalaInstance,
      logger: Logger,
      ioScheduler: Scheduler
  ): Option[ClassLoader] = {
    import ch.epfl.scala.debugadapter.BuildInfo._
    import coursier._
    val scalaVersion = scalaInstance.version
    val module = s"${expressionCompilerName}_$scalaVersion"
    val expressionCompilerDep = Dependency(
      Module(
        Organization(expressionCompilerOrganization),
        ModuleName(module)
      ),
      expressionCompilerVersion
    )
    val resolution = Fetch()
      .addDependencies(expressionCompilerDep)
      .either()(ioScheduler)

    resolution match {
      case Left(error) =>
        logger.warn(
          s"Failed fetching $expressionCompilerOrganization:$module:$expressionCompilerVersion"
        )
        logger.warn(error.getMessage)
        logger.warn(s"Expression evaluation will not work.")
        None
      case Right(files) =>
        val expressionCompilerJar = files
          .find(_.getName.startsWith(expressionCompilerName))
          .map(_.toURI.toURL)
        val evaluationClassLoader =
          new URLClassLoader(expressionCompilerJar.toArray, scalaInstance.loader)
        Some(evaluationClassLoader)
    }
  }

  private def getAllDepsRecursively(build: Build, project: Project): Seq[Project] = {
    @tailrec def tailApply(projects: Seq[Project], acc: Set[Project]): Seq[Project] = {
      if (projects.isEmpty) acc.toSeq
      else {
        val dependencies = projects
          .flatMap(_.dependencies)
          .flatMap(build.getProjectFor)
          .distinct
          .filterNot(acc.contains)
        tailApply(dependencies, acc ++ dependencies)
      }
    }
    tailApply(Seq(project), Set(project))
  }

  private def getLibraries(allProjects: Seq[Project]): Seq[ClassPathEntry] = {
    allProjects
      .flatMap(_.resolution)
      .flatMap(_.modules)
      .distinct
      .flatMap { module =>
        for {
          classJar <- module.artifacts.find(_.classifier.isEmpty)
          sourceJar <- module.artifacts.find(_.classifier.contains("sources"))
        } yield {
          val sourceEntry = SourceJar(sourceJar.path)
          ClassPathEntry(classJar.path, Seq(sourceEntry))
        }
      }
      .distinct
  }

  private def getClassDirectories(allProjects: Seq[Project]): Seq[ClassPathEntry] = {
    allProjects.map { project =>
      val sourceBuffer = mutable.Buffer.empty[SourceEntry]
      for (sourcePath <- project.sources) {
        if (sourcePath.isDirectory) {
          sourceBuffer += SourceDirectory(sourcePath.underlying)
        } else {
          sourceBuffer += StandaloneSourceFile(
            sourcePath.underlying,
            sourcePath.underlying.getFileName.toString
          )
        }
      }
      for (glob <- project.sourcesGlobs) {
        glob.walkThrough { file =>
          sourceBuffer += StandaloneSourceFile(
            file.underlying,
            file.toRelative(glob.directory).toString
          )
        }
      }
      ClassPathEntry(project.out.underlying, sourceBuffer.toSeq)
    }
  }
}
