package bloop.dap

import java.io.Closeable

import scala.collection.mutable
import scala.concurrent.Future

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.ScalaMainClass
import ch.epfl.scala.debugadapter._

import bloop.cli.ExitStatus
import bloop.data.ClientInfo
import bloop.data.JdkConfig
import bloop.data.Platform
import bloop.data.Project
import bloop.engine.Dag
import bloop.engine.State
import bloop.engine.tasks.RunMode
import bloop.engine.tasks.Tasks
import bloop.io.AbsolutePath
import bloop.task.Task
import bloop.testing.DebugLoggingEventHandler
import bloop.testing.TestInternals

import monix.execution.Ack
import monix.execution.Scheduler
import monix.reactive.Observable

abstract class BloopDebuggee(
    initialState: State,
    classUpdates: Observable[Seq[String]]
)(implicit ioScheduler: Scheduler)
    extends Debuggee {
  protected def scalaVersionOpt: Option[String]
  // The version doesn't matter for project without Scala version (Java only)
  override val scalaVersion = ScalaVersion(scalaVersionOpt.getOrElse("2.13.8"))

  override def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val debugSessionLogger = new DebuggeeLogger(listener, initialState.logger)

    val task = start(initialState.copy(logger = debugSessionLogger), listener)
      .map { status =>
        if (!status.isOk) throw new Exception(s"debuggee failed with ${status.name}")
      }
    DapCancellableFuture.runAsync(task)
  }

  override def observeClassUpdates(onClassUpdate: Seq[String] => Unit): Closeable = {
    val subscription =
      classUpdates.subscribe(onClassUpdate.andThen(_ => Future.successful(Ack.Continue)))
    () => subscription.cancel()
  }

  protected def start(state: State, listener: DebuggeeListener): Task[ExitStatus]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    val modules: Seq[Module],
    val libraries: Seq[Library],
    val unmanagedEntries: Seq[UnmanagedEntry],
    val classUpdates: Observable[Seq[String]],
    env: JdkConfig,
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggee(initialState, classUpdates)(ioScheduler) {

  protected def scalaVersionOpt: Option[String] = project.scalaInstance.map(_.version)

  val javaRuntime: Option[JavaRuntime] = JavaRuntime(env.javaHome.underlying)
  def name: String = s"${getClass.getSimpleName}(${project.name}, ${mainClass.className})"
  def start(state: State, listener: DebuggeeListener): Task[ExitStatus] = {
    // TODO: https://github.com/scalacenter/bloop/issues/1456
    // Metals used to add the `-J` prefix but it is not needed anymore
    // So we cautiously strip it off
    val jvmOptions = mainClass.jvmOptions.map(_.stripPrefix("-J"))
    val runState = Tasks.runJVM(
      state,
      project,
      env,
      project.workspaceDirectory.getOrElse(project.baseDirectory),
      mainClass.className,
      mainClass.arguments.toArray,
      jvmOptions.toArray,
      mainClass.environmentVariables.getOrElse(Nil),
      RunMode.Debug
    )
    runState.map(_.status)
  }
}

private final class TestSuiteDebugAdapter(
    projects: Seq[Project],
    testClasses: bsp.ScalaTestSuites,
    val modules: Seq[Module],
    val libraries: Seq[Library],
    val unmanagedEntries: Seq[UnmanagedEntry],
    val javaRuntime: Option[JavaRuntime],
    val classUpdates: Observable[Seq[String]],
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggee(initialState, classUpdates)(ioScheduler) {

  protected def scalaVersionOpt: Option[String] =
    projects.headOption.flatMap(_.scalaInstance.map(_.version))

  override def name: String = {
    val projectsStr = projects.map(_.bspUri).mkString("[", ", ", "]")
    val selectedTests = testClasses.suites
      .map { suite =>
        val tests = suite.tests.mkString("(", ",", ")")
        s"${suite.className}$tests"
      }
      .mkString("[", ", ", "]")
    s"${getClass.getSimpleName}($projectsStr, $selectedTests)"
  }
  override def start(state: State, listener: DebuggeeListener): Task[ExitStatus] = {
    val filter = TestInternals.parseFilters(testClasses.suites.map(_.className))
    val handler = new DebugLoggingEventHandler(state.logger, listener)

    val task = Tasks.test(
      state,
      projects.toList,
      Nil,
      filter,
      testClasses,
      handler,
      mode = RunMode.Debug
    )

    task.map(_.status)
  }
}

private final class AttachRemoteDebugAdapter(
    projects: Seq[Project],
    val modules: Seq[Module],
    val libraries: Seq[Library],
    val unmanagedEntries: Seq[UnmanagedEntry],
    val javaRuntime: Option[JavaRuntime],
    val classUpdates: Observable[Seq[String]],
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggee(initialState, classUpdates)(ioScheduler) {

  protected def scalaVersionOpt: Option[String] =
    projects.headOption.flatMap(_.scalaInstance.map(_.version))

  override def name: String = s"${getClass.getSimpleName}(${initialState.build.origin})"
  override def start(state: State, listener: DebuggeeListener): Task[ExitStatus] = Task(
    ExitStatus.Ok
  )
}

object BloopDebuggeeRunner {
  def forMainClass(
      projects: Seq[Project],
      mainClass: ScalaMainClass,
      state: State,
      ioScheduler: Scheduler
  ): Either[String, Debuggee] = {
    projects match {
      case Seq() => Left(s"No projects specified for main class: [$mainClass]")
      case Seq(project) =>
        project.platform match {
          case jvm: Platform.Jvm =>
            val (modules, libraries, unmanagedEntries, classUpdates) =
              getEntriesAndClassUpdates(project, state)
            Right(
              new MainClassDebugAdapter(
                project,
                mainClass,
                modules,
                libraries,
                unmanagedEntries,
                classUpdates,
                jvm.runtimeConfig.getOrElse(jvm.config),
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
      testClasses: bsp.ScalaTestSuites,
      state: State,
      ioScheduler: Scheduler
  ): Either[String, Debuggee] = {
    projects match {
      case Seq() =>
        Left(
          s"No projects specified for the test suites: [${testClasses.suites.map(_.className).sorted}]"
        )
      case Seq(project) if project.platform.isInstanceOf[Platform.Jvm] =>
        val (modules, libraries, unmanagedEntries, classUpdates) =
          getEntriesAndClassUpdates(project, state)
        val Platform.Jvm(config, _, _, runtimeConfig, _, _) = project.platform
        val javaRuntime = JavaRuntime(runtimeConfig.getOrElse(config).javaHome.underlying)
        Right(
          new TestSuiteDebugAdapter(
            projects,
            testClasses,
            modules,
            libraries,
            unmanagedEntries,
            javaRuntime,
            classUpdates,
            state,
            ioScheduler
          )
        )

      case _ =>
        Right(
          new TestSuiteDebugAdapter(
            projects,
            testClasses,
            Seq.empty,
            Seq.empty,
            Seq.empty,
            None,
            Observable.empty,
            state,
            ioScheduler
          )
        )

    }
  }

  def forAttachRemote(
      projects: Seq[Project],
      state: State,
      ioScheduler: Scheduler
  ): Debuggee = {
    projects match {
      case Seq(project) if project.platform.isInstanceOf[Platform.Jvm] =>
        val (modules, libraries, unmanagedEntries, classUpdates) =
          getEntriesAndClassUpdates(project, state)
        val Platform.Jvm(config, _, _, runtimeConfig, _, _) = project.platform
        val javaRuntime = JavaRuntime(runtimeConfig.getOrElse(config).javaHome.underlying)
        new AttachRemoteDebugAdapter(
          Seq(project),
          modules,
          libraries,
          unmanagedEntries,
          javaRuntime,
          classUpdates,
          state,
          ioScheduler
        )
      case projects =>
        new AttachRemoteDebugAdapter(
          projects,
          Seq.empty,
          Seq.empty,
          Seq.empty,
          None,
          Observable.empty,
          state,
          ioScheduler
        )
    }
  }

  private def getEntriesAndClassUpdates(
      project: Project,
      state: State
  ): (Seq[Module], Seq[Library], Seq[UnmanagedEntry], Observable[Seq[String]]) = {
    val dag = state.build.getDagFor(project)
    val projects = Dag.dfs(dag, mode = Dag.PreOrder)
    val modules = getModules(projects, state.client)
    val libraries = getLibraries(projects)
    val fullClasspath = project.fullClasspath(dag, state.client)
    val unmanagedEntries = getUnmanagedEntries(fullClasspath, modules ++ libraries)
    val allClassUpdates = projects.map(state.client.getClassesObserverFor(_).observable)
    val mergedClassUpdates = Observable.fromIterable(allClassUpdates).merge
    (modules, libraries, unmanagedEntries, mergedClassUpdates)
  }

  private def getLibraries(projects: Seq[Project]): Seq[Library] = {
    projects
      .flatMap(_.resolution)
      .flatMap(_.modules)
      .distinct
      .flatMap { module =>
        for {
          classJar <- module.artifacts.find(_.classifier.isEmpty)
          sourceJar <- module.artifacts.find(_.classifier.contains("sources"))
        } yield {
          val sourceEntry = SourceJar(sourceJar.path)
          Library(module.name, module.version, classJar.path, Seq(sourceEntry))
        }
      }
      .distinct
  }

  private def getUnmanagedEntries(
      fullClasspath: Seq[AbsolutePath],
      managedEntries: Seq[ManagedEntry]
  ): Seq[UnmanagedEntry] = {
    val managedPaths = managedEntries.map(_.absolutePath).toSet
    fullClasspath
      .collect { case p if !managedPaths.contains(p.underlying) => UnmanagedEntry(p.underlying) }
  }

  private def getModules(projects: Seq[Project], client: ClientInfo): Seq[Module] = {
    projects.map { project =>
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
      val projectName = project.bspUri.toString
      val scalaVersion = project.scalaInstance.map(si => ScalaVersion(si.version))
      Module(projectName, scalaVersion, project.scalacOptions, classDir.underlying, sourceBuffer)
    }
  }
}
