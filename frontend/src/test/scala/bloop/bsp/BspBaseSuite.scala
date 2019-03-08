package bloop.bsp

import bloop.engine.{State, ExecutionContext}
import bloop.cli.{Commands}
import bloop.testing.BaseSuite

import bloop.cli.{Commands, CommonOptions, Validate, CliOptions, BspProtocol}
import bloop.data.{Project, ClientInfo}
import bloop.engine.{State, Run}
import bloop.engine.caches.ResultsCache
import bloop.internal.build.BuildInfo
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, DebugFilter, RecordingLogger, Slf4jAdapter, Logger}
import bloop.util.{TestUtil, TestProject}

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints

import monix.eval.Task
import monix.reactive.observers.BufferedSubscriber
import monix.reactive.subjects.ConcurrentSubject
import monix.reactive.{Observable, MulticastStrategy, Observer}
import monix.execution.{ExecutionModel, Scheduler, CancelableFuture}
import monix.execution.atomic.AtomicInt

import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import sbt.internal.util.MessageOnlyException

import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer, Response, Services}

import monix.execution.Scheduler

abstract class BspBaseSuite extends BaseSuite with BspClientTest {
  final class UnmanagedBspTestState(
      state: State,
      closeServer: Task[Unit],
      currentCompileIteration: AtomicInt,
      diagnostics: ConcurrentHashMap[bsp.BuildTargetIdentifier, StringBuilder],
      implicit val client: LanguageClient,
      private val serverStates: Observable[State]
  ) {

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
  }

  final class ManagedBspTestState(
      state: State,
      lastBspStatus: bsp.StatusCode,
      currentCompileIteration: AtomicInt,
      diagnostics: ConcurrentHashMap[bsp.BuildTargetIdentifier, StringBuilder],
      implicit val client0: LanguageClient,
      private val serverStates: Observable[State]
  ) {
    val underlying = state
    val client = state.client
    val status = state.status
    val results = state.results

    def compileTask(project: TestProject): Task[ManagedBspTestState] = {
      import endpoints.{Workspace, BuildTarget}
      // Use a default timeout of 30 seconds for every operation
      Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(e) => fail("The request for build targets in ${state.build.origin} failed!")
        case Right(ts) =>
          ts.targets.map(_.id).find(_ == project.bspId) match {
            case Some(target) =>
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
            case None => fail(s"Target ${project.bspId} is missing in the workspace! Found ${ts}")
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

    def lastDiagnostics(project: TestProject): String = {
      Option(diagnostics.get(project.bspId)).map(_.mkString).getOrElse("")
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

  private final lazy val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  def createBspCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    protocol match {
      case BspProtocol.Tcp => createTcpBspCommand(configDir)
      case BspProtocol.Local => createLocalBspCommand(configDir, tempDir)
    }
  }

  def loadBspState(
      workspace: AbsolutePath,
      projects: List[TestProject],
      logger: RecordingLogger
  )(runTest: ManagedBspTestState => Unit): Unit = {
    val bspLogger = new BspClientLogger(logger)
    val configDir = TestProject.populateWorkspace(workspace, projects)
    val bspCommand = createBspCommand(configDir)
    val state = TestUtil.loadTestProject(configDir.underlying, logger)
    openBspConnection(state, bspCommand, configDir, bspLogger).withinSession(runTest(_))
  }

  def openBspConnection[T](
      state: State,
      cmd: Commands.ValidatedBsp,
      configDirectory: AbsolutePath,
      logger: BspClientLogger[_],
      allowError: Boolean = false,
      userScheduler: Option[Scheduler] = None
  ): UnmanagedBspTestState = {
    val compileIteration = AtomicInt(0)
    val readyToConnect = Some(Promise[Unit]())
    val subject = ConcurrentSubject.behavior[State](state)(ExecutionContext.ioScheduler)
    val scheduler = userScheduler.getOrElse(bspDefaultScheduler)
    val path = RelativePath(configDirectory.underlying.getFileName)
    val bspServer = BspServer.run(cmd, state, path, readyToConnect, Some(subject), scheduler)
    val bspServerStarted = bspServer.runAsync(scheduler)
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

      implicit val lsClient = new LanguageClient(out, logger)
      val messages = BaseProtocolMessage.fromInputStream(in, logger)
      val addDiagnosticsHandler =
        addServicesTest(configDirectory, () => compileIteration.get, addToStringReport)
      val services = addDiagnosticsHandler(TestUtil.createTestServices(false, logger))
      val lsServer = new LanguageServer(messages, lsClient, services, scheduler, logger)
      val runningClientServer = lsServer.startTask.runAsync(scheduler)

      val cwd = configDirectory.underlying.getParent
      val initializeServer = endpoints.Build.initialize.request(
        bsp.InitializeBuildParams(
          "test-bloop-client",
          "1.0.0",
          BuildInfo.bspVersion,
          rootUri = bsp.Uri(cwd.toAbsolutePath.toUri),
          capabilities = bsp.BuildClientCapabilities(List("scala", "java")),
          None
        )
      )

      val initializedTask = {
        // Add just a slight delay of 10ms before starting the connection
        initializeServer.delayExecution(FiniteDuration(10, "ms")).flatMap { _ =>
          Task.fromFuture(endpoints.Build.initialized.notify(bsp.InitializedBuildParams()))
        }
      }

      val closeTask = {
        endpoints.Build.shutdown.request(bsp.Shutdown()).flatMap { _ =>
          Task.fromFuture(endpoints.Build.exit.notify(bsp.Exit())).map { _ =>
            BspServer.closeSocket(cmd, socket)
            cleanUpLastResources(cmd)
          }
        }
      }

      initializedTask.map { _ =>
        (closeTask.memoize, lsClient, subject)
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    val bspClient = bspClientExecution.runAsync(scheduler)

    try {
      // The timeout for all our bsp tests, no matter what operation they run, is 30s
      val (closeServer, client, stateObservable) = Await.result(bspClient, FiniteDuration(30, "s"))
      new UnmanagedBspTestState(
        state,
        closeServer,
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
}
