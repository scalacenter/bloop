package bloop.bsp

import bloop.engine.{State, ExecutionContext}
import bloop.cli.{Commands}
import bloop.testing.BaseSuite

import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.cli.{Commands, CommonOptions, Validate, CliOptions, BspProtocol}
import bloop.data.{Project, ClientInfo}
import bloop.engine.{State, Run}
import bloop.engine.caches.ResultsCache
import bloop.internal.build.BuildInfo
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, DebugFilter, RecordingLogger, Slf4jAdapter, Logger}
import bloop.util.{TestUtil, TestProject, CrossPlatform}

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints

import monix.eval.Task
import monix.reactive.observers.BufferedSubscriber
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{Observable, MulticastStrategy, Observer}
import monix.execution.{ExecutionModel, Scheduler, CancelableFuture}
import monix.execution.atomic.AtomicInt

import sbt.internal.util.MessageOnlyException

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer, Response, Services}

import monix.execution.Scheduler
import ch.epfl.scala.bsp.Uri
import io.circe.Json

abstract class BspBaseSuite extends BaseSuite with BspClientTest {
  final class UnmanagedBspTestState(
      state: State,
      closeServer: Task[Unit],
      closeStreamsForcibly: () => Unit,
      currentCompileIteration: AtomicInt,
      diagnostics: ConcurrentHashMap[bsp.BuildTargetIdentifier, StringBuilder],
      implicit val client: BloopLanguageClient,
      private val serverStates: Observable[State]
  ) {
    val status = state.status

    def toUnsafeManagedState: ManagedBspTestState = {
      new ManagedBspTestState(
        state,
        bsp.StatusCode.Ok,
        currentCompileIteration,
        diagnostics,
        client,
        serverStates
      )
    }

    def withinSession(f: ManagedBspTestState => Unit): Unit = {
      try f(
        new ManagedBspTestState(
          state,
          bsp.StatusCode.Ok,
          currentCompileIteration,
          diagnostics,
          client,
          serverStates
        )
      )
      finally {
        TestUtil.await(FiniteDuration(1, "s"))(closeServer)
      }
    }

    def simulateClientDroppingOut(): Unit = closeStreamsForcibly()
  }

  final class ManagedBspTestState(
      state: State,
      lastBspStatus: bsp.StatusCode,
      currentCompileIteration: AtomicInt,
      diagnostics: ConcurrentHashMap[bsp.BuildTargetIdentifier, StringBuilder],
      implicit val client0: BloopLanguageClient,
      val serverStates: Observable[State]
  ) {
    val underlying = state
    val client = state.client
    val status = state.status
    val results = state.results

    import endpoints.{Workspace, BuildTarget}
    def findBuildTarget(project: TestProject): bsp.BuildTarget = {
      val workspaceTargetTask = {
        Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).map {
          case Left(e) => fail("The request for build targets in ${state.build.origin} failed!")
          case Right(ts) =>
            ts.targets.map(t => t.id -> t).find(_._1 == project.bspId) match {
              case Some((_, target)) => target
              case None => fail(s"Target ${project.bspId} is missing in the workspace! Found ${ts}")
            }
        }
      }

      TestUtil.await(FiniteDuration(5, "s"))(workspaceTargetTask)
    }

    def workspaceTargets: bsp.WorkspaceBuildTargetsResult = {
      val workspaceTargetsTask = {
        Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).map {
          case Left(e) => fail("The request for build targets in ${state.build.origin} failed!")
          case Right(ts) => ts
        }
      }

      TestUtil.await(FiniteDuration(5, "s"))(workspaceTargetsTask)
    }

    def runAfterTargets[T](
        project: TestProject
    )(f: bsp.BuildTargetIdentifier => Task[T]): Task[T] = {
      Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(e) => fail("The request for build targets in ${state.build.origin} failed!")
        case Right(ts) =>
          ts.targets.map(_.id).find(_ == project.bspId) match {
            case Some(target) => f(target)
            case None => fail(s"Target ${project.bspId} is missing in the workspace! Found ${ts}")
          }
      }
    }

    def compileTask(project: TestProject): Task[ManagedBspTestState] = {
      runAfterTargets(project) { target =>
        // Handle internal state before sending compile request
        diagnostics.clear()
        currentCompileIteration.increment(1)

        BuildTarget.compile.request(bsp.CompileParams(List(target), None, None)).flatMap {
          case Right(r) =>
            // `headL` returns latest saved state from bsp because source is behavior subject
            serverStates.headL.map { state =>
              new ManagedBspTestState(
                state,
                r.statusCode,
                currentCompileIteration,
                diagnostics,
                client0,
                serverStates
              )
            }
          case Left(e) => fail(s"Compilation error for request ${e.id}:\n${e.error}")
        }
      }
    }

    def compileHandle(
        project: TestProject,
        delay: Option[FiniteDuration] = None
    ): CancelableFuture[ManagedBspTestState] = {
      val interpretedTask = {
        val task = compileTask(project)
        delay match {
          case Some(duration) => task.delayExecution(duration)
          case None => task
        }
      }

      interpretedTask.runAsync(ExecutionContext.scheduler)
    }

    def compile(project: TestProject): ManagedBspTestState = {
      // Use a default timeout of 30 seconds for every operation
      TestUtil.await(FiniteDuration(30, "s")) {
        compileTask(project)
      }
    }

    def requestSources(project: TestProject): bsp.SourcesResult = {
      val sourcesTask = {
        endpoints.BuildTarget.sources.request(bsp.SourcesParams(List(project.bspId))).map {
          case Left(error) => fail(s"Received error ${error}")
          case Right(sources) => sources
        }
      }

      TestUtil.await(FiniteDuration(5, "s"))(sourcesTask)
    }

    def requestResources(project: TestProject): bsp.ResourcesResult = {
      val resourcesTask = {
        endpoints.BuildTarget.resources.request(bsp.ResourcesParams(List(project.bspId))).map {
          case Left(error) => fail(s"Received error ${error}")
          case Right(resources) => resources
        }
      }

      TestUtil.await(FiniteDuration(5, "s"))(resourcesTask)
    }

    def requestDependencySources(project: TestProject): bsp.DependencySourcesResult = {
      val dependencySourcesTask = {
        endpoints.BuildTarget.dependencySources
          .request(bsp.DependencySourcesParams(List(project.bspId)))
          .map {
            case Left(error) => fail(s"Received error ${error}")
            case Right(sources) => sources
          }
      }

      TestUtil.await(FiniteDuration(5, "s"))(dependencySourcesTask)
    }

    import bloop.cli.ExitStatus
    def toBspStatus(status: ExitStatus): bsp.StatusCode = {
      status match {
        case ExitStatus.Ok => bsp.StatusCode.Ok
        case _ => bsp.StatusCode.Error
      }
    }

    def mainClasses(project: TestProject): bsp.ScalaMainClassesResult = {
      val task = runAfterTargets(project) { target =>
        val params = bsp.ScalaMainClassesParams(List(target), None)
        endpoints.BuildTarget.scalaMainClasses.request(params).map {
          case Left(error) => fail(s"Received error $error")
          case Right(result) => result
        }
      }

      TestUtil.await(FiniteDuration(5, "s"))(task)
    }

    def testClasses(project: TestProject): bsp.ScalaTestClassesResult = {
      val task = runAfterTargets(project) { target =>
        val params = bsp.ScalaTestClassesParams(List(target), None)
        endpoints.BuildTarget.scalaTestClasses.request(params).map {
          case Left(error) => fail(s"Received error $error")
          case Right(result) => result
        }
      }

      TestUtil.await(FiniteDuration(5, "s"))(task)
    }

    def scalaOptions(project: TestProject): (ManagedBspTestState, bsp.ScalacOptionsResult) = {
      val scalacOptionsTask = runAfterTargets(project) { target =>
        endpoints.BuildTarget.scalacOptions.request(bsp.ScalacOptionsParams(List(target))).map {
          case Left(error) => fail(s"Received error ${error}")
          case Right(options) => options
        }
      }

      TestUtil.await(FiniteDuration(5, "s")) {
        scalacOptionsTask.flatMap { result =>
          serverStates.headL.map { state =>
            val latestServerState = new ManagedBspTestState(
              state,
              toBspStatus(state.status),
              currentCompileIteration,
              diagnostics,
              client0,
              serverStates
            )

            latestServerState -> result
          }
        }
      }
    }

    def lastDiagnostics(project: TestProject): String = {
      Option(diagnostics.get(project.bspId)).map(_.mkString).getOrElse("")
    }

    def backup: ManagedBspTestState = {
      val newState = this.toTestState.backup.state

      new ManagedBspTestState(
        newState,
        this.lastBspStatus,
        this.currentCompileIteration,
        this.diagnostics,
        this.client0,
        this.serverStates
      )
    }

    def toTestState: TestState = new TestState(state)
    def toTestStateFrom(origin: TestState): TestState = {
      val originState = origin.state
      new TestState(
        state.copy(
          logger = originState.logger,
          client = originState.client,
          pool = originState.pool,
          commonOptions = originState.commonOptions
        )
      )
    }
  }

  // We limit the scheduler on purpose so that we don't have any thread leak.
  private val bspDefaultScheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution
  )

  /** The protocol to use for the inheriting test suite. */
  def protocol: BspProtocol

  override def test(name: String)(fun: => Any): Unit = {
    if (isWindows && protocol == BspProtocol.Local) {
      // https://github.com/scalacenter/bloop/issues/281
      super.ignore(name, "DISABLED")(fun)
    } else {
      super.test(name)(fun)
    }
  }

  private final lazy val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  def createBspCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    protocol match {
      case BspProtocol.Tcp =>
        val portNumber = 5202 + scala.util.Random.nextInt(2000)
        createTcpBspCommand(configDir, portNumber)
      case BspProtocol.Local => createLocalBspCommand(configDir, tempDir)
    }
  }

  case class ManagedBspTestBuild(state: ManagedBspTestState, projects: List[TestProject]) {
    val rawState = state.underlying
    def projectFor(name: String): TestProject = {
      projects.find(_.config.name == name).get
    }
    def configFileFor(project: TestProject): AbsolutePath = {
      rawState.build.getProjectFor(project.config.name).get.origin.path
    }
  }

  def loadBspBuildFromResources(
      buildName: String,
      workspace: AbsolutePath,
      logger: RecordingLogger
  )(runTest: ManagedBspTestBuild => Unit): Unit = {
    val testBuild = loadBuildFromResources(buildName, workspace, logger)
    val testState = testBuild.state
    val configDir = testState.build.origin
    val bspLogger = new BspClientLogger(logger)
    val bspCommand = createBspCommand(configDir)
    openBspConnection(testState.state, bspCommand, configDir, bspLogger).withinSession { bspState =>
      val bspTestBuild = ManagedBspTestBuild(bspState, testBuild.projects)
      runTest(bspTestBuild)
    }
  }

  def loadBspState(
      workspace: AbsolutePath,
      projects: List[TestProject],
      logger: RecordingLogger,
      clientClassesRootDir: Option[AbsolutePath] = None
  )(runTest: ManagedBspTestState => Unit): Unit = {
    val bspLogger = new BspClientLogger(logger)
    val configDir = TestProject.populateWorkspace(workspace, projects)
    val bspCommand = createBspCommand(configDir)
    val state = TestUtil.loadTestProject(configDir.underlying, logger)
    openBspConnection(
      state,
      bspCommand,
      configDir,
      bspLogger,
      clientClassesRootDir = clientClassesRootDir
    ).withinSession(runTest(_))
  }

  def openBspConnection[T](
      state: State,
      cmd: Commands.ValidatedBsp,
      configDirectory: AbsolutePath,
      logger: BspClientLogger[_],
      allowError: Boolean = false,
      userIOScheduler: Option[Scheduler] = None,
      userComputationScheduler: Option[Scheduler] = None,
      clientClassesRootDir: Option[AbsolutePath] = None
  ): UnmanagedBspTestState = {
    val compileIteration = AtomicInt(0)
    val readyToConnect = Promise[Unit]()
    val subject = ConcurrentSubject.behavior[State](state)(ExecutionContext.ioScheduler)
    val computationScheduler = userComputationScheduler.getOrElse(ExecutionContext.scheduler)
    val ioScheduler = userIOScheduler.getOrElse(bspDefaultScheduler)
    val path = RelativePath(configDirectory.underlying.getFileName)
    val bspServer = BspServer.run(
      cmd,
      state,
      path,
      Some(readyToConnect),
      Some(subject),
      computationScheduler,
      ioScheduler
    )

    val bspServerStarted = bspServer.runAsync(ioScheduler)
    val stringifiedDiagnostics = new ConcurrentHashMap[bsp.BuildTargetIdentifier, StringBuilder]()
    val bspClientExecution = establishClientConnection(cmd).flatMap { socket =>
      val in = socket.getInputStream
      val out = socket.getOutputStream

      def addToStringReport(
          btid: bsp.BuildTargetIdentifier,
          add: StringBuilder => StringBuilder
      ): Unit = {
        val f = (b: StringBuilder) => add(if (b == null) new StringBuilder() else b)
        stringifiedDiagnostics.compute(btid, (_, builder0) => f(builder0))
        ()
      }

      implicit val lsClient = new BloopLanguageClient(out, logger)
      val messages = BaseProtocolMessage.fromInputStream(in, logger)
      val addDiagnosticsHandler =
        addServicesTest(configDirectory, () => compileIteration.get, addToStringReport)
      val services = addDiagnosticsHandler(TestUtil.createTestServices(false, logger))
      import bloop.bsp.BloopLanguageServer

      val lsServer = new BloopLanguageServer(messages, lsClient, services, ioScheduler, logger)
      val runningClientServer = lsServer.startTask.runAsync(ioScheduler)

      val initializeData: Option[Json] = {
        clientClassesRootDir
          .map(d => Uri(d.toBspUri))
          .map(uri => BloopExtraBuildParams.encoder(BloopExtraBuildParams(Some(uri))))
      }

      val cwd = configDirectory.underlying.getParent
      val initializeServer = endpoints.Build.initialize.request(
        bsp.InitializeBuildParams(
          "test-bloop-client",
          "1.0.0",
          BuildInfo.bspVersion,
          rootUri = bsp.Uri(cwd.toAbsolutePath.toUri),
          capabilities = bsp.BuildClientCapabilities(List("scala", "java")),
          initializeData
        )
      )

      val initializedTask = {
        val startedServer = Task.fromFuture(readyToConnect.future)
        initializeServer.delayExecutionWith(startedServer).flatMap { _ =>
          Task.fromFuture(endpoints.Build.initialized.notify(bsp.InitializedBuildParams()))
        }
      }

      val closeTask = {
        endpoints.Build.shutdown.request(bsp.Shutdown()).flatMap { _ =>
          Task.fromFuture(endpoints.Build.exit.notify(bsp.Exit())).map { _ =>
            socket.close()
            cleanUpLastResources(cmd)
          }
        }
      }

      // This task closes the streams to simulate a client dropping out,
      // but doesn't properly close the server. This happens on purpose.
      val closeStreamsForcibly = () => {
        socket.close()
      }

      initializedTask.map { _ =>
        (closeTask.memoize, closeStreamsForcibly, lsClient, subject)
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    val bspClient = bspClientExecution.runAsync(ioScheduler)

    try {
      // The timeout for all our bsp tests, no matter what operation they run, is 30s
      val (closeServer, closeStreamsForcibly, client, stateObservable) =
        Await.result(bspClient, FiniteDuration(30, "s"))
      new UnmanagedBspTestState(
        state,
        closeServer,
        closeStreamsForcibly,
        compileIteration,
        stringifiedDiagnostics,
        client,
        stateObservable
      )
    } catch {
      case t: Throwable =>
        bspServerStarted.cancel()
        cleanUpLastResources(cmd)
        t match {
          case e: ExecutionException => throw e.getCause
          case _ => throw t
        }
        throw t
    }
  }

  def assertInvalidCompilationState(
      state: ManagedBspTestState,
      projects: List[TestProject],
      existsAnalysisFile: Boolean,
      hasPreviousSuccessful: Boolean,
      hasSameContentsInClassesDir: Boolean
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertInvalidCompilationState(
      state.toTestState,
      projects,
      existsAnalysisFile,
      hasPreviousSuccessful,
      hasSameContentsInClassesDir
    )
  }

  def assertEmptyCompilationState(
      state: ManagedBspTestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertEmptyCompilationState(state.toTestState, projects)
  }

  def assertValidCompilationState(
      state: ManagedBspTestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertValidCompilationState(state.toTestState, projects)
  }

  def assertDifferentExternalClassesDirs(
      s1: ManagedBspTestState,
      s2: ManagedBspTestState,
      project: TestProject
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertDifferentExternalClassesDirs(s1.toTestState, s2.toTestState, project)
  }

  def assertSameExternalClassesDirs(
      s1: ManagedBspTestState,
      s2: ManagedBspTestState,
      project: TestProject
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertSameExternalClassesDirs(s1.toTestState, s2.toTestState, project)
  }

  def assertDifferentExternalClassesDirs(
      s1: ManagedBspTestState,
      s2: ManagedBspTestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertDifferentExternalClassesDirs(s1.toTestState, s2.toTestState, projects)
  }

  def assertSameExternalClassesDirs(
      s1: ManagedBspTestState,
      s2: ManagedBspTestState,
      projects: List[TestProject]
  )(implicit filename: sourcecode.File, line: sourcecode.Line): Unit = {
    assertSameExternalClassesDirs(s1.toTestState, s2.toTestState, projects)
  }

  def mapBoth[A1, A2](f1: CancelableFuture[A1], f2: CancelableFuture[A2]): Task[(A1, A2)] = {
    Task.mapBoth(Task.fromFuture(f1), Task.fromFuture(f2))((a1, a2) => a1 -> a2)
  }
}
