package bloop.dap

import bloop.ScalaInstance
import bloop.cli.ExitStatus
import bloop.data.{ClientInfo, JdkConfig, Platform, Project}
import bloop.engine.caches.ExpressionCompilerCache
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.engine.{Dag, State}
import bloop.logging.Logger
import bloop.testing.{LoggingEventHandler, TestInternals}
import ch.epfl.scala.bsp.ScalaMainClass
import ch.epfl.scala.debugadapter._
import monix.eval.Task
import monix.execution.Scheduler

import java.net.URLClassLoader
import scala.collection.mutable
import java.nio.file.Path

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
    ioScheduler: Scheduler,
    override val classPath: Seq[Path]
) extends BloopDebuggeeRunner(initialState, ioScheduler) {
  val javaRuntime: Option[JavaRuntime] = JavaRuntime(env.javaHome.underlying)
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
    ioScheduler: Scheduler,
    override val classPath: Seq[Path]
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
    ioScheduler: Scheduler,
    override val classPath: Seq[Path]
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
            val classPathEntries = getClassPathEntries(state, project)
            val evaluationClassLoader = getEvaluationClassLoader(project, state)
            val classpath = getClasspath(state, project)
            Right(
              new MainClassDebugAdapter(
                project,
                mainClass,
                classPathEntries,
                evaluationClassLoader,
                jvm.config,
                state,
                ioScheduler,
                classpath
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
        val classPathEntries = getClassPathEntries(state, project)
        val javaRuntime = JavaRuntime(config.javaHome.underlying)
        val evaluationClassLoader = getEvaluationClassLoader(project, state)
        val classpath = getClasspath(state, project)
        Right(
          new TestSuiteDebugAdapter(
            projects,
            filters,
            classPathEntries,
            javaRuntime,
            evaluationClassLoader,
            state,
            ioScheduler,
            classpath
          )
        )
      case _ =>
        Right(
          new TestSuiteDebugAdapter(
            projects,
            filters,
            Seq.empty,
            None,
            None,
            state,
            ioScheduler,
            Seq.empty
          )
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
        val classPathEntries = getClassPathEntries(state, project)
        val javaRuntime = JavaRuntime(config.javaHome.underlying)
        val evaluationClassLoader = getEvaluationClassLoader(project, state)
        val classpath = getClasspath(state, project)
        new AttachRemoteDebugAdapter(
          classPathEntries,
          javaRuntime,
          evaluationClassLoader,
          state,
          ioScheduler,
          classpath
        )
      case _ => new AttachRemoteDebugAdapter(Seq.empty, None, None, state, ioScheduler, Seq.empty)
    }
  }

  private def getClassPathEntries(state: State, project: Project): Seq[ClassPathEntry] = {
    val dag = state.build.getDagFor(project)
    getLibraries(dag) ++ getClassDirectories(dag, state.client)
  }

  private def getEvaluationClassLoader(
      project: Project,
      state: State
  ): Option[ClassLoader] = {
    project.scalaInstance
      .flatMap(getEvaluationClassLoader(_, state.logger))
  }

  private def getEvaluationClassLoader(
      scalaInstance: ScalaInstance,
      logger: Logger
  ): Option[ClassLoader] = {
    ExpressionCompilerCache.fetch(scalaInstance.version, logger) match {
      case Left(error) =>
        logger.warn(error)
        logger.warn(s"Expression evaluation will not work.")
        None
      case Right(expressionCompilerJar) =>
        val evaluationClassLoader =
          new URLClassLoader(Array(expressionCompilerJar.toFile.toURI.toURL), scalaInstance.loader)
        Some(evaluationClassLoader)
    }
  }

  private def getLibraries(dag: Dag[Project]): Seq[ClassPathEntry] = {
    Dag
      .dfs(dag)
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

  private def getClasspath(state: State, project: Project): Seq[Path] = {
    val dag = state.build.getDagFor(project)
    project.fullClasspath(dag, state.client).map(_.underlying).toSeq
  }

  private def getClassDirectories(dag: Dag[Project], client: ClientInfo): Seq[ClassPathEntry] = {
    Dag.dfs(dag).map { project =>
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
      val classDir = client.getUniqueClassesDirFor(project, forceGeneration = true)
      ClassPathEntry(classDir.underlying, sourceBuffer)
    }
  }
}
