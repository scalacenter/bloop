package bloop.dap

import scala.collection.mutable

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
import bloop.task.Task
import bloop.testing.DebugLoggingEventHandler
import bloop.testing.TestInternals

import monix.execution.Scheduler
import io.reactivex.Observable

abstract class BloopDebuggee(
    initialState: State,
    ioScheduler: Scheduler
) extends Debuggee {
  val debuggeeScalaVersion: Option[String]
  // The version doesn't matter for project without Scala version (Java only)
  lazy val scalaVersion = ScalaVersion(debuggeeScalaVersion.getOrElse("2.13.8"))

  override def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
    val debugSessionLogger = new DebuggeeLogger(listener, initialState.logger)

    val task = start(initialState.copy(logger = debugSessionLogger), listener)
      .map { status =>
        if (!status.isOk) throw new Exception(s"debuggee failed with ${status.name}")
      }
    DapCancellableFuture.runAsync(task, ioScheduler)
  }

  protected def start(state: State, listener: DebuggeeListener): Task[ExitStatus]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    val modules: Seq[Module],
    val libraries: Seq[Library],
    val unmanagedEntries: Seq[UnmanagedEntry],
    env: JdkConfig,
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggee(initialState, ioScheduler) {

  override val debuggeeScalaVersion = project.scalaInstance.map(_.version)

  val classesToUpdate: Observable[Seq[String]] = project.classObserver

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
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggee(initialState, ioScheduler) {

  override val debuggeeScalaVersion = projects.headOption.flatMap(_.scalaInstance.map(_.version))

  val classesToUpdate: Observable[Seq[String]] =
    projects.map(_.classObserver).fold(Observable.empty[Seq[String]])(_ mergeWith _)

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
    initialState: State,
    ioScheduler: Scheduler
) extends BloopDebuggee(initialState, ioScheduler) {

  override val debuggeeScalaVersion = projects.headOption.flatMap(_.scalaInstance.map(_.version))

  override def name: String = s"${getClass.getSimpleName}(${initialState.build.origin})"
  override def start(state: State, listener: DebuggeeListener): Task[ExitStatus] = Task(
    ExitStatus.Ok
  )
  val classesToUpdate: Observable[Seq[String]] =
    projects.map(_.classObserver).fold(Observable.empty[Seq[String]])(_ mergeWith _)
}

object BloopDebuggeeRunner {
  def getEntries(
      project: Project,
      state: State
  ): (Seq[Module], Seq[Library], Seq[UnmanagedEntry]) = {
    val dag = state.build.getDagFor(project)
    val modules = getModules(dag, state.client)
    val libraries = getLibraries(dag)
    val unmanagedEntries =
      getUnmanagedEntries(project, dag, state.client, modules ++ libraries)
    (modules, libraries, unmanagedEntries)
  }

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
            val (modules, libraries, unmanagedEntries) = getEntries(project, state)
            Right(
              new MainClassDebugAdapter(
                project,
                mainClass,
                modules,
                libraries,
                unmanagedEntries,
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
        val (modules, libraries, unmanagedEntries) = getEntries(project, state)
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
        val (modules, libraries, unmanagedEntries) = getEntries(project, state)
        val Platform.Jvm(config, _, _, runtimeConfig, _, _) = project.platform
        val javaRuntime = JavaRuntime(runtimeConfig.getOrElse(config).javaHome.underlying)
        new AttachRemoteDebugAdapter(
          Seq(project),
          modules,
          libraries,
          unmanagedEntries,
          javaRuntime,
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
          state,
          ioScheduler
        )
    }
  }

  private def getLibraries(dag: Dag[Project]): Seq[Library] = {
    Dag
      .dfs(dag, mode = Dag.PreOrder)
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
      project: Project,
      dag: Dag[Project],
      client: ClientInfo,
      managedEntries: Seq[ManagedEntry]
  ): Seq[UnmanagedEntry] = {
    val managedPaths = managedEntries.map(_.absolutePath).toSet
    val fullClasspath = project.fullClasspath(dag, client).map(_.underlying).toSeq
    fullClasspath
      .filter(p => !managedPaths.contains(p))
      .map(UnmanagedEntry.apply)
  }

  private def getModules(dag: Dag[Project], client: ClientInfo): Seq[Module] = {
    Dag.dfs(dag, mode = Dag.PreOrder).map { project =>
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
      Module(
        projectName,
        scalaVersion,
        project.scalacOptions,
        classDir.underlying,
        sourceBuffer.toSeq
      )
    }
  }
}
