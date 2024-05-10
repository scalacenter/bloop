package bloop.bsp

import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.BuildTargetIdentifier
import ch.epfl.scala.bsp.CompileResult
import ch.epfl.scala.bsp.MessageType
import ch.epfl.scala.bsp.ShowMessageParams
import ch.epfl.scala.bsp.StatusCode
import ch.epfl.scala.bsp.Uri
import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.debugadapter.DebugServer
import ch.epfl.scala.debugadapter.Debuggee

import bloop.Compiler
import bloop.ScalaInstance
import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.cli.Commands
import bloop.cli.ExitStatus
import bloop.cli.Validate
import bloop.config.Config
import bloop.dap.BloopDebugToolsResolver
import bloop.dap.BloopDebuggeeRunner
import bloop.dap.DebugServerLogger
import bloop.data.ClientInfo
import bloop.data.ClientInfo.BspClientInfo
import bloop.data.JdkConfig
import bloop.data.Platform
import bloop.data.Project
import bloop.data.WorkspaceSettings
import bloop.engine.Aggregate
import bloop.engine.Dag
import bloop.engine.Feedback
import bloop.engine.Interpreter
import bloop.engine.State
import bloop.engine.tasks.CompileTask
import bloop.engine.tasks.RunMode
import bloop.engine.tasks.Tasks
import bloop.engine.tasks.TestTask
import bloop.engine.tasks.compilation.CompileClientStore
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.engine.tasks.toolchains.ScalaNativeToolchain
import bloop.exec.Forker
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.io.RelativePath
import bloop.logging.BspServerLogger
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.reporter.BspProjectReporter
import bloop.reporter.ProblemPerPhase
import bloop.reporter.ReporterConfig
import bloop.reporter.ReporterInputs
import bloop.task.Task
import bloop.testing.LoggingEventHandler
import bloop.testing.TestInternals
import bloop.util.JavaRuntime

import com.github.plokhotnyuk.jsoniter_scala.core._
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import jsonrpc4s._
import monix.execution.Cancelable
import monix.execution.CancelablePromise
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean
import monix.execution.atomic.AtomicInt
import monix.reactive.subjects.BehaviorSubject

final class BloopBspServices(
    callSiteState: State,
    client: BloopLanguageClient,
    relativeConfigPath: RelativePath,
    stopBspServer: CancelablePromise[Unit],
    observer: Option[BehaviorSubject[State]],
    isClientConnected: AtomicBoolean,
    connectedBspClients: ConcurrentHashMap[ClientInfo.BspClientInfo, AbsolutePath],
    computationScheduler: Scheduler,
    ioScheduler: Scheduler
) {
  private implicit val debugFilter: DebugFilter = DebugFilter.Bsp
  private type BspResponse[T] = Either[Response.Error, T]

  /** The return type of every endpoint implementation */
  private type BspEndpointResponse[T] = Task[BspResponse[T]]

  /** The return type of intermediate BSP computations */
  private type BspResult[T] = Task[(State, BspResponse[T])]

  /** The return type of a bsp computation wrapped by `ifInitialized` */
  private type BspComputation[T] = (State, BspServerLogger) => BspResult[T]

  /**
   * Schedule the async response handlers to run on the default computation
   * thread pool and leave the serialization/deserialization work (bsp4s
   * library work) to the IO thread pool. This is critical for performance.
   */
  def schedule[T](t: BspEndpointResponse[T]): BspEndpointResponse[T] = {
    t.executeOn(computationScheduler).asyncBoundary(ioScheduler)
  }

  private val backgroundDebugServers = TrieMap.empty[URI, Cancelable]

  // Disable ansi codes for now so that the BSP clients don't get unescaped color codes
  private val taskIdCounter: AtomicInt = AtomicInt(0)
  private val baseBspLogger = BspServerLogger(callSiteState, client, taskIdCounter, false)

  final val services: BloopRpcServices = BloopRpcServices
    .empty(baseBspLogger)
    .requestAsync(endpoints.Build.initialize)(p => schedule(initialize(p)))
    .notification(endpoints.Build.initialized)(_ => initialized())
    .request(endpoints.Build.shutdown)(_ => shutdown())
    .notificationAsync(endpoints.Build.exit)(_ => exit())
    .requestAsync(endpoints.Workspace.buildTargets)(_ => schedule(buildTargets()))
    .requestAsync(endpoints.BuildTarget.sources)(p => schedule(sources(p)))
    .requestAsync(endpoints.BuildTarget.inverseSources)(p => schedule(inverseSources(p)))
    .requestAsync(endpoints.BuildTarget.resources)(p => schedule(resources(p)))
    .requestAsync(endpoints.BuildTarget.outputPaths)(p => schedule(outputPaths(p)))
    .requestAsync(endpoints.BuildTarget.scalacOptions)(p => schedule(scalacOptions(p)))
    .requestAsync(endpoints.BuildTarget.javacOptions)(p => schedule(javacOptions(p)))
    .requestAsync(endpoints.BuildTarget.compile)(p => schedule(compile(p)))
    .requestAsync(endpoints.BuildTarget.test)(p => schedule(test(p)))
    .requestAsync(endpoints.BuildTarget.run)(p => schedule(run(p)))
    .requestAsync(endpoints.BuildTarget.cleanCache)(p => schedule(clean(p)))
    .requestAsync(endpoints.BuildTarget.scalaMainClasses)(p => schedule(scalaMainClasses(p)))
    .requestAsync(endpoints.BuildTarget.scalaTestClasses)(p => schedule(scalaTestClasses(p)))
    .requestAsync(endpoints.BuildTarget.dependencySources)(p => schedule(dependencySources(p)))
    .requestAsync(endpoints.BuildTarget.dependencyModules)(p => schedule(dependencyModules(p)))
    .requestAsync(endpoints.DebugSession.start)(p => schedule(startDebugSession(p)))
    .requestAsync(endpoints.BuildTarget.jvmTestEnvironment)(p => schedule(jvmTestEnvironment(p)))
    .requestAsync(endpoints.BuildTarget.jvmRunEnvironment)(p => schedule(jvmRunEnvironment(p)))
    .notificationAsync(BloopBspDefinitions.stopClientCaching)(p => stopClientCaching(p))

  // Internal state, initial value defaults to
  @volatile private var currentState: State = callSiteState

  /** Returns the final state after BSP commands that can be cached by bloop. */
  def stateAfterExecution: State = {
    // Use logger of the initial state instead of the bsp forwarder logger
    val nextState0 = currentState.copy(logger = callSiteState.logger)
    clientInfo.future.value match {
      case Some(scala.util.Success(clientInfo)) => nextState0.copy(client = clientInfo)
      case _ => nextState0
    }
  }

  private val previouslyFailedCompilations = new TrieMap[Project, Compiler.Result.Failed]()
  private def reloadState(
      config: AbsolutePath,
      clientInfo: ClientInfo,
      clientSettings: Option[WorkspaceSettings],
      bspLogger: BspServerLogger
  ): Task[State] = {
    val pool = currentState.pool
    val defaultOpts = currentState.commonOptions
    bspLogger.debug(s"Reloading bsp state for ${config.syntax}")
    State
      .loadActiveStateFor(config, clientInfo, pool, defaultOpts, bspLogger, clientSettings)
      .map { state0 =>
        /* Create a new state that has the previously compiled results in this BSP
         * client as the last compiled result available for a project. This is required
         * because in diagnostics reporting in BSP is stateful. When compilations
         * happen in other clients, the previous result does not contain the list of
         * previous problems (that tracks where we reported diagnostics) that this client
         * had and therefore we can fail to reset diagnostics. */
        val newState = {
          val previous = previouslyFailedCompilations.toMap
          state0.copy(
            results = state0.results.replacePreviousResults(previous),
            client = clientInfo
          )
        }

        currentState = newState
        newState
      }
  }

  private def saveState(state: State, bspLogger: BspServerLogger): Task[Unit] = {
    Task {
      val configDir = state.build.origin
      bspLogger.debug(s"Saving bsp state for ${configDir.syntax}")
      // Save the state globally so that it can be accessed by other clients
      State.stateCache.updateBuild(state)
      publishStateInObserver(state)
    }.flatten
  }

  // Completed whenever the initialization happens, used in `initialized`
  val clientInfo: Promise[BspClientInfo] = Promise[ClientInfo.BspClientInfo]()
  val clientInfoTask: Task[BspClientInfo] = Task.fromFuture(clientInfo.future).memoize

  /**
   * Unregisters this client if the BSP services registered one.
   *
   * This method is typically called from `BspServer` when a client is
   * disconnected for any reason.
   */
  def unregisterClient: Option[ClientInfo.BspClientInfo] = {
    Cancelable.cancelAll(backgroundDebugServers.values)
    clientInfo.future.value match {
      case None => None
      case Some(client) =>
        client match {
          case Success(client) =>
            val configDir = currentState.build.origin
            connectedBspClients.remove(client, configDir)
            Some(client)
          case Failure(_) => None
        }
    }
  }

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param params The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(
      params: bsp.InitializeBuildParams
  ): BspEndpointResponse[bsp.InitializeBuildResult] = {
    val bspLogger = baseBspLogger
    val uri = new URI(params.rootUri.value)
    val configDir = AbsolutePath(uri).resolve(relativeConfigPath)
    val extraBuildParams = parseBloopExtraParams(params.data)
    val ownsBuildFiles = extraBuildParams.flatMap(_.ownsBuildFiles).getOrElse(false)
    val clientClassesRootDir = extraBuildParams.flatMap(extra =>
      extra.clientClassesRootDir.map(dir => AbsolutePath(dir.toPath))
    )
    val currentWorkspaceSettings = WorkspaceSettings.readFromFile(configDir, callSiteState.logger)
    val currentRefreshProjectsCommand: Option[List[String]] =
      currentWorkspaceSettings.flatMap(_.refreshProjectsCommand)
    val currentTraceSettings = currentWorkspaceSettings.flatMap(_.traceSettings)

    val isMetals = params.displayName.contains("Metals")
    val isIntelliJ = params.displayName.contains("IntelliJ")
    val refreshProjectsCommand = if (isIntelliJ) currentRefreshProjectsCommand else None

    val client = ClientInfo.BspClientInfo(
      params.displayName,
      params.version,
      params.bspVersion,
      ownsBuildFiles,
      clientClassesRootDir,
      refreshProjectsCommand,
      () => isClientConnected.get
    )

    /**
     * A Metals BSP client enables a special transformation of a build via the
     * workspace settings. These workspace settings contains all of the
     * information required by bloop to enable Metals-specific settings in
     * every project of a build so that users from different build tools don't
     * need to manually enable these in their build.
     */
    val metalsSettings: Option[WorkspaceSettings] = {
      if (!isMetals) {
        currentWorkspaceSettings
      } else {
        val javaSemanticDBVersion = extraBuildParams.flatMap(_.javaSemanticdbVersion)
        val scalaSemanticDBVersion = extraBuildParams.flatMap(_.semanticdbVersion)
        val supportedScalaVersions =
          if (scalaSemanticDBVersion.nonEmpty)
            extraBuildParams.map(_.supportedScalaVersions.toList.flatten)
          else None
        if (javaSemanticDBVersion.nonEmpty || scalaSemanticDBVersion.nonEmpty)
          Some(
            WorkspaceSettings(
              javaSemanticDBVersion,
              scalaSemanticDBVersion,
              supportedScalaVersions,
              currentRefreshProjectsCommand,
              currentTraceSettings
            )
          )
        else None
      }
    }

    reloadState(configDir, client, metalsSettings, bspLogger).flatMap { state =>
      callSiteState.logger.info(s"request received: build/initialize")
      clientInfo.success(client)
      connectedBspClients.put(client, configDir)
      publishStateInObserver(state.copy(client = client)).map { _ =>
        Right(
          bsp.InitializeBuildResult(
            BuildInfo.bloopName,
            BuildInfo.version,
            BuildInfo.bspVersion,
            bsp.BuildServerCapabilities(
              compileProvider = Some(BloopBspServices.DefaultCompileProvider),
              testProvider = Some(BloopBspServices.DefaultTestProvider),
              runProvider = Some(BloopBspServices.DefaultRunProvider),
              debugProvider = Some(BloopBspServices.DefaultDebugProvider),
              inverseSourcesProvider = Some(true),
              dependencySourcesProvider = Some(true),
              dependencyModulesProvider = Some(true),
              resourcesProvider = Some(true),
              outputPathsProvider = Some(true),
              buildTargetChangedProvider = Some(false),
              jvmTestEnvironmentProvider = Some(true),
              jvmRunEnvironmentProvider = Some(true),
              canReload = Some(false)
            ),
            None,
            None
          )
        )
      }
    }
  }

  private def publishStateInObserver(state: State): Task[Unit] = {
    observer match {
      case None => Task.unit
      case Some(observer) => Task.fromFuture(observer.onNext(state)).map(_ => ())
    }
  }

  private def parseBloopExtraParams(data: Option[RawJson]): Option[BloopExtraBuildParams] = {
    data.flatMap { json =>
      try Some(readFromArray[BloopExtraBuildParams](json.value))
      catch {
        case e: Exception =>
          callSiteState.logger.warn(
            s"Unexpected error decoding bloop-specific initialize params: ${e.getMessage()}"
          )
          None
      }
    }
  }

  val isInitialized: Promise[BspResponse[Unit]] = scala.concurrent.Promise[BspResponse[Unit]]()
  val isInitializedTask: Task[BspResponse[Unit]] = Task.fromFuture(isInitialized.future).memoize

  def initialized(): Unit = {
    isInitialized.success(Right(()))
    callSiteState.logger.info("BSP initialization handshake complete.")
  }

  def ifInitialized[T](
      originId: Option[String]
  )(compute: BspComputation[T]): Task[BspResponse[T]] = {
    val bspLogger = baseBspLogger.withOriginId(originId)
    // Give a time window for `isInitialized` to complete, otherwise assume it didn't happen
    isInitializedTask
      .flatMap(_ => clientInfoTask.map(Right(_)))
      .timeoutTo(
        FiniteDuration(1, TimeUnit.SECONDS),
        Task.now(Left(Response.invalidRequest("The session has not been initialized.")))
      )
      .flatMap {
        case Left(e) => Task.now(Left(e))
        case Right(clientInfo) =>
          reloadState(currentState.build.origin, clientInfo, None, bspLogger).flatMap { state =>
            compute(state, bspLogger).flatMap {
              case (state, e) => saveState(state, bspLogger).map(_ => e)
            }
          }
      }
  }

  def mapToProject(
      target: bsp.BuildTargetIdentifier,
      state: State
  ): Either[String, ProjectMapping] = {
    val uri = target.uri
    ProjectUris.getProjectDagFromUri(uri.value, state) match {
      case Left(errorMsg) => Left(errorMsg)
      case Right(Some(project)) => Right((target, project))
      case Right(None) => Left(s"No project associated with $uri")
    }
  }

  type ProjectMapping = (bsp.BuildTargetIdentifier, Project)
  private def mapToProjects(
      targets: Seq[bsp.BuildTargetIdentifier],
      state: State
  ): Either[String, Seq[ProjectMapping]] = {
    if (targets.isEmpty) {
      Left("Empty build targets. Expected at least one build target identifier.")
    } else {
      val zero: Either[String, List[ProjectMapping]] = Right(Nil)
      targets.foldLeft(zero) { (acc, t) =>
        acc.flatMap(ms => mapToProject(t, state).map(m => m :: ms))
      }
    }
  }

  /**
   * Keep track of those projects that were compiled at least once so that we can
   * decide to enable fresh reporting for projects that are compiled for the first time.
   *
   * Required by https://github.com/scalacenter/bloop/issues/726
   */
  private val compiledTargetsAtLeastOnce = new TrieMap[bsp.BuildTargetIdentifier, Boolean]()

  private val originToCompileStores = new TrieMap[String, CompileClientStore.ConcurrentStore]()

  def stopClientCaching(params: BloopBspDefinitions.StopClientCachingParams): Task[Unit] = {
    Task.eval { originToCompileStores.remove(params.originId); () }.executeAsync
  }

  def compileProjects(
      userProjects: Seq[ProjectMapping],
      state: State,
      compileArgs: List[String],
      originId: Option[String],
      logger: BspServerLogger
  ): BspResult[bsp.CompileResult] = {
    val cancelCompilation = Promise[Unit]()
    def reportError(p: Project, problems: List[ProblemPerPhase], elapsedMs: Long): String = {
      // Don't show warnings in this "final report", we're handling them in the reporter
      val count = bloop.reporter.Problem.count(problems)
      s"${p.name} [${elapsedMs}ms] (errors ${count.errors})"
    }

    val isPipeline = compileArgs.exists(_ == "--pipeline")
    def compile(projects: List[Project]): Task[State] = {
      val config = ReporterConfig.defaultFormat.copy(reverseOrder = false)

      val isSbtClient = state.client match {
        case info: BspClientInfo if info.name == "sbt" => true
        case _ => false
      }

      val createReporter = (inputs: ReporterInputs[BspServerLogger]) => {
        val btid = bsp.BuildTargetIdentifier(inputs.project.bspUri)
        val reportAllPreviousProblems = {
          val report = compiledTargetsAtLeastOnce.putIfAbsent(btid, true) match {
            case Some(_) => false
            case None => true
          }
          if (isSbtClient) false
          else report
        }

        new BspProjectReporter(
          inputs.project,
          inputs.logger,
          inputs.cwd,
          config,
          reportAllPreviousProblems
        )
      }

      val dag = Aggregate(projects.map(p => state.build.getDagFor(p)))
      val store = {
        if (!isSbtClient) CompileClientStore.NoStore
        else {
          originId match {
            case None => CompileClientStore.NoStore
            case Some(originId) =>
              val newStore = new CompileClientStore.ConcurrentStore()
              originToCompileStores.putIfAbsent(originId, newStore) match {
                case Some(store) => store
                case None => newStore
              }
          }
        }
      }

      CompileTask.compile(
        state,
        dag,
        createReporter,
        isPipeline,
        cancelCompilation,
        store,
        logger
      )
    }

    val projects: List[Project] = {
      val projects0 = Dag.reduce(state.build.dags, userProjects.map(_._2).toSet).toList
      if (!compileArgs.exists(_ == "--cascade")) projects0
      else Dag.inverseDependencies(state.build.dags, projects0).reduced
    }

    compile(projects).map { newState =>
      val compiledResults = state.results.diffLatest(newState.results)
      val errorMsgs = compiledResults.flatMap {
        case (p, result) =>
          result match {
            case Compiler.Result.Empty => Nil
            case Compiler.Result.Blocked(_) => Nil
            case Compiler.Result.Success(_, _, _, _, _, _, _) =>
              previouslyFailedCompilations.remove(p)
              Nil
            case Compiler.Result.GlobalError(problem, _) => List(problem)
            case Compiler.Result.Cancelled(problems, elapsed, _) =>
              List(reportError(p, problems, elapsed))
            case f @ Compiler.Result.Failed(problems, t, elapsed, _) =>
              previouslyFailedCompilations.put(p, f)
              val acc = List(reportError(p, problems, elapsed))
              t match {
                case Some(t) => s"Bloop error when compiling ${p.name}: '${t.getMessage}'" :: acc
                case None => acc
              }
          }
      }

      val response: Either[Response.Error, bsp.CompileResult] = {
        if (cancelCompilation.isCompleted)
          Right(bsp.CompileResult(originId, bsp.StatusCode.Cancelled, None, None))
        else {
          errorMsgs match {
            case Nil => Right(bsp.CompileResult(originId, bsp.StatusCode.Ok, None, None))
            case _ => Right(bsp.CompileResult(originId, bsp.StatusCode.Error, None, None))
          }
        }
      }

      (newState, response)
    }
  }

  def compile(params: bsp.CompileParams): BspEndpointResponse[bsp.CompileResult] = {
    ifInitialized(params.originId) { (state: State, logger0: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger0.error(error)
          Task.now((state, Right(bsp.CompileResult(None, bsp.StatusCode.Error, None, None))))
        case Right(mappings) =>
          val compileArgs = params.arguments.getOrElse(Nil)
          val isVerbose = compileArgs.exists(_ == "--verbose")
          val logger = if (isVerbose) logger0.asBspServerVerbose else logger0
          compileProjects(mappings, state, compileArgs, params.originId, logger)
      }
    }
  }

  def clean(params: bsp.CleanCacheParams): BspEndpointResponse[bsp.CleanCacheResult] = {
    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          val msg = s"Couldn't map all targets to clean to projects in the build: $error"
          Task.now((state, Right(bsp.CleanCacheResult(Some(msg), cleaned = false))))
        case Right(mappings) =>
          val projectsToClean = mappings.map(_._2).toList
          Tasks.clean(state, projectsToClean, includeDeps = false).materialize.map {
            case Success(state) => (state, Right(bsp.CleanCacheResult(None, cleaned = true)))
            case Failure(exception) =>
              val t = Logger.prettyPrintException(exception)
              val msg = s"Unexpected error when cleaning build targets!${lineSeparator}$t"
              state -> Right(bsp.CleanCacheResult(Some(msg), cleaned = false))
          }
      }
    }
  }

  def scalaTestClasses(
      params: bsp.ScalaTestClassesParams
  ): BspEndpointResponse[bsp.ScalaTestClassesResult] =
    ifInitialized(params.originId) { (state: State, logger: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Right(bsp.ScalaTestClassesResult(Nil))))

        case Right(projects) =>
          val subTasks = projects.toList.filter(p => TestTask.isTestProject(p._2)).map {
            case (id, project) =>
              val task = TestTask.findTestNamesWithFramework(project, state)
              val item = task.map { classes =>
                classes
                  .groupBy(_.framework)
                  .map {
                    case (framework, classes) =>
                      bsp.ScalaTestClassesItem(id, Some(framework), classes.flatMap(_.classes))
                  }
                  .toList
              }
              item
          }

          Task.sequence(subTasks).map { items =>
            val result = bsp.ScalaTestClassesResult(items.flatten)
            (state, Right(result))
          }
      }
    }

  def startDebugSession(
      params: bsp.DebugSessionParams
  ): BspEndpointResponse[bsp.DebugSessionAddress] = {

    def inferDebuggee(projects: Seq[Project], state: State): BspResponse[Debuggee] = {
      def convert[A: JsonValueCodec](
          f: A => Either[String, Debuggee]
      ): Either[Response.Error, Debuggee] = {
        params.data match {
          case Some(data) =>
            Try(readFromArray[A](data.value)) match {
              case Failure(error) =>
                Left(Response.invalidRequest(error.getMessage()))
              case Success(params) =>
                f(params) match {
                  case Right(adapter) => Right(adapter)
                  case Left(error) => Left(Response.invalidRequest(error))
                }
            }
          case None =>
            Left(Response.invalidRequest("No debug data available"))
        }

      }

      params.dataKind match {
        case Some(bsp.DebugSessionParamsDataKind.ScalaMainClass) =>
          convert[bsp.ScalaMainClass](main =>
            BloopDebuggeeRunner.forMainClass(projects, main, state, ioScheduler)
          )
        case Some(bsp.TestParamsDataKind.ScalaTestSuites) =>
          implicit val codec = JsonCodecMaker.make[List[String]]
          convert[List[String]](classNames => {
            val testClasses = bsp.ScalaTestSuites(
              classNames.map(className => bsp.ScalaTestSuiteSelection(className, Nil)),
              Nil,
              Nil
            )
            BloopDebuggeeRunner.forTestSuite(projects, testClasses, state, ioScheduler)
          })
        case Some(bsp.TestParamsDataKind.ScalaTestSuitesSelection) =>
          convert[bsp.ScalaTestSuites](testClasses => {
            BloopDebuggeeRunner.forTestSuite(projects, testClasses, state, ioScheduler)
          })
        case Some(bsp.DebugSessionParamsDataKind.ScalaAttachRemote) =>
          Right(BloopDebuggeeRunner.forAttachRemote(projects, state, ioScheduler))
        case dataKind => Left(Response.invalidRequest(s"Unsupported data kind: $dataKind"))
      }
    }

    ifInitialized(None) { (state, logger) =>
      JavaRuntime.loadJavaDebugInterface match {
        case Failure(exception) =>
          val message = JavaRuntime.current match {
            case JavaRuntime.JDK => Feedback.detectedJdkWithoutJDI(exception)
            case JavaRuntime.JRE => Feedback.detectedUnsupportedJreForDebugging(exception)
          }
          Task.now((state, Left(Response.internalError(message))))

        case Success(_) =>
          mapToProjects(params.targets, state) match {
            case Left(error) =>
              // Log the mapping error to the user via a log event + an error status code
              logger.error(error)
              Task.now((state, Left(Response.invalidRequest(error))))
            case Right(mappings) =>
              // FIXME: Add origin id to DAP request
              compileProjects(mappings, state, Nil, None, logger).flatMap {
                case (state, Left(error)) =>
                  Task.now((state, Left(error)))
                case (state, Right(result)) if result.statusCode != bsp.StatusCode.Ok =>
                  Task.now(
                    (state, Left(Response.internalError("Compilation not successful")))
                  )
                case (state, Right(_)) =>
                  val projects = mappings.map(_._2)

                  inferDebuggee(projects, state) match {
                    case Right(debuggee) =>
                      val dapLogger = new DebugServerLogger(logger)
                      val resolver = new BloopDebugToolsResolver(logger)
                      val handler =
                        DebugServer.run(
                          debuggee,
                          resolver,
                          dapLogger,
                          gracePeriod = Duration(5, TimeUnit.SECONDS)
                        )(ioScheduler)
                      val listenAndUnsubscribe = Task
                        .fromFuture(handler.running)
                        .map(_ => backgroundDebugServers -= handler.uri)
                        .runAsync(ioScheduler)
                      backgroundDebugServers += handler.uri -> listenAndUnsubscribe
                      Task.now(
                        (state, Right(new bsp.DebugSessionAddress(bsp.Uri(handler.uri.toString()))))
                      )

                    case Left(error) =>
                      Task.now((state, Left(error)))
                  }
              }
          }
      }
    }
  }

  def test(params: bsp.TestParams): BspEndpointResponse[bsp.TestResult] = {
    def test(project: Project, state: State): Task[Tasks.TestRuns] = {
      val testFilter = TestInternals.parseFilters(Nil) // Don't support test only for now
      val handler = new LoggingEventHandler(state.logger)
      Tasks.test(
        state,
        List(project),
        Nil,
        testFilter,
        bsp.ScalaTestSuites(Nil, Nil, Nil),
        handler,
        mode = RunMode.Normal
      )
    }

    val originId = params.originId
    ifInitialized(originId) { (state: State, logger0: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger0.error(error)
          Task.now((state, Right(bsp.TestResult(originId, bsp.StatusCode.Error, None, None))))
        case Right(mappings) =>
          val args = params.arguments.getOrElse(Nil)
          val logger = logger0.asBspServerVerbose
          compileProjects(mappings, state, args, originId, logger).flatMap {
            case (newState, Right(CompileResult(_, StatusCode.Ok, _, _))) =>
              val sequentialTestExecution: Task[Seq[Tasks.TestRuns]] =
                Task.sequence(mappings.map { case (_, p) => test(p, newState) })

              sequentialTestExecution.materialize.map {
                case Success(testRunsSeq) =>
                  testRunsSeq.reduceOption(_ ++ _) match {
                    case None =>
                      (newState, Right(bsp.TestResult(originId, bsp.StatusCode.Ok, None, None)))
                    case Some(testRuns) =>
                      val status = testRuns.status
                      val bspStatus =
                        if (status == ExitStatus.Ok) bsp.StatusCode.Ok else bsp.StatusCode.Error
                      (
                        newState.mergeStatus(status),
                        Right(bsp.TestResult(originId, bspStatus, None, None))
                      )
                  }
                case Failure(e) =>
                  val errorMessage =
                    Response.internalError(s"Failed test execution: ${e.getMessage}")
                  (newState, Left(errorMessage))
              }

            case (newState, Right(CompileResult(_, errorCode, _, _))) =>
              Task.now((newState, Right(bsp.TestResult(originId, errorCode, None, None))))

            case (newState, Left(error)) =>
              Task.now((newState, Left(error)))
          }
      }
    }
  }

  def jvmRunEnvironment(
      params: bsp.JvmRunEnvironmentParams
  ): BspEndpointResponse[bsp.JvmRunEnvironmentResult] =
    jvmEnvironment(params.targets).map(_.map(new bsp.JvmRunEnvironmentResult(_)))

  def jvmTestEnvironment(
      params: bsp.JvmTestEnvironmentParams
  ): BspEndpointResponse[bsp.JvmTestEnvironmentResult] =
    jvmEnvironment(params.targets).map(_.map(new bsp.JvmTestEnvironmentResult(_)))

  def jvmEnvironment(
      targets: Seq[BuildTargetIdentifier]
  ): BspEndpointResponse[List[bsp.JvmEnvironmentItem]] = {
    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Left(Response.invalidRequest(error))))

        case Right(projects) =>
          val environmentEntries = (for {
            (id, project) <- projects.toList
            dag = state.build.getDagFor(project)
            fullClasspath = project.fullRuntimeClasspath(dag, state.client).map(_.toBspUri.toString)
            environmentVariables = state.commonOptions.env.toMap
            workingDirectory = project.workingDirectory.toString
            javaOptions <- project.runtimeJdkConfig.map(_.javaOptions.toList)
            classNames = Tasks.findMainClasses(state, project)
          } yield {
            bsp.JvmEnvironmentItem(
              id,
              fullClasspath.toList,
              javaOptions,
              workingDirectory,
              environmentVariables,
              Some(classNames.map(bsp.JvmMainClass(_, Nil)))
            )
          }).toList
          Task.now((state, Right(environmentEntries)))
      }
    }
  }

  def scalaMainClasses(
      params: bsp.ScalaMainClassesParams
  ): BspEndpointResponse[bsp.ScalaMainClassesResult] = {
    def findMainClasses(state: State, project: Project): List[bsp.ScalaMainClass] =
      for {
        className <- Tasks.findMainClasses(state, project)
      } yield bsp.ScalaMainClass(className, Nil, Nil, None)

    ifInitialized(params.originId) { (state: State, logger: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Right(bsp.ScalaMainClassesResult(Nil, params.originId))))

        case Right(projects) =>
          val items = for {
            (id, project) <- projects.toList
            mainClasses = findMainClasses(state, project)
          } yield bsp.ScalaMainClassesItem(id, mainClasses)

          val result = new bsp.ScalaMainClassesResult(items, params.originId)
          Task.now((state, Right(result)))
      }
    }
  }

  def run(params: bsp.RunParams): BspEndpointResponse[bsp.RunResult] = {
    def parseMainClass(project: Project, state: State): Either[Exception, bsp.ScalaMainClass] = {
      params.dataKind match {
        case Some(bsp.RunParamsDataKind.ScalaMainClass) =>
          params.data match {
            case None =>
              Left(new IllegalStateException(s"Missing data for $params"))
            case Some(json) =>
              try Right(readFromArray[bsp.ScalaMainClass](json.value))
              catch {
                case e: Exception => Left(e)
              }
          }
        case Some(kind) =>
          Left(new IllegalArgumentException(s"Unsupported data kind: $kind"))
        case None =>
          val cmd = Commands.Run(List(project.name))
          Interpreter.getMainClass(state, project, cmd.main) match {
            case Right(name) =>
              Right(new bsp.ScalaMainClass(name, cmd.args, Nil, None))
            case Left(_) =>
              Left(new IllegalStateException(s"Main class for project $project not found"))
          }
      }
    }

    def run(
        project: Project,
        state: State
    ): Task[State] = {
      import bloop.engine.tasks.LinkTask.{linkMainWithJs, linkMainWithNative}
      val cwd = state.commonOptions.workingPath

      parseMainClass(project, state) match {
        case Left(error) =>
          Task.now(sys.error(s"Failed to run main class in $project due to: ${error.getMessage}"))
        case Right(mainClass) =>
          project.platform match {
            case Platform.Jvm(compileConfig0, _, _, _, _, _) =>
              val config0 = project.runtimeJdkConfig.getOrElse(compileConfig0)
              val mainArgs = mainClass.arguments.toArray
              val config = JdkConfig(config0.javaHome, config0.javaOptions ++ mainClass.jvmOptions)
              Tasks.runJVM(
                state,
                project,
                config,
                cwd,
                mainClass.className,
                mainArgs,
                skipJargs = false,
                mainClass.environmentVariables.getOrElse(Nil),
                RunMode.Normal
              )
            case platform @ Platform.Native(config, _, _) =>
              val cmd = Commands.Run(List(project.name))
              val target = ScalaNativeToolchain.linkTargetFrom(project, config)
              linkMainWithNative(cmd, project, state, mainClass.className, target, platform)
                .flatMap { state =>
                  val args = (target.syntax +: cmd.args).toArray
                  if (!state.status.isOk) Task.now(state)
                  else Tasks.runNativeOrJs(state, cwd, args)
                }
            case platform @ Platform.Js(config, _, _) =>
              val cmd = Commands.Run(List(project.name))
              val targetDir = ScalaJsToolchain.linkTargetFrom(project, config)
              linkMainWithJs(cmd, project, state, mainClass.className, targetDir, platform)
                .flatMap { state =>
                  val files = targetDir.list.map(_.toString())
                  // We use node to run the program (is this a special case?)
                  val args = ("node" +: files ::: cmd.args).toArray
                  if (!state.status.isOk) Task.now(state)
                  else Tasks.runNativeOrJs(state, cwd, args)
                }
          }
      }
    }

    val originId = params.originId
    ifInitialized(originId) { (state: State, logger0: BspServerLogger) =>
      mapToProject(params.target, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger0.error(error)
          Task.now((state, Right(bsp.RunResult(originId, bsp.StatusCode.Error))))
        case Right((tid, project)) =>
          val args = params.arguments.getOrElse(Nil)
          val logger = logger0.asBspServerVerbose
          compileProjects(List((tid, project)), state, args, originId, logger).flatMap {
            case (newState, compileResult) =>
              compileResult match {
                case Right(_) =>
                  var isCancelled: Boolean = false
                  run(project, newState)
                    .doOnCancel(Task { isCancelled = true; () })
                    .materialize
                    .map(_.toEither)
                    .map {
                      case Left(e) =>
                        val errorMsg =
                          Response.internalError(s"Failed test execution: ${e.getMessage}")
                        (state, Left(errorMsg))
                      case Right(state) =>
                        val status = {
                          val exitStatus = state.status
                          if (isCancelled) bsp.StatusCode.Cancelled
                          else if (exitStatus.isOk) bsp.StatusCode.Ok
                          else bsp.StatusCode.Error
                        }
                        (state, Right(bsp.RunResult(originId, status)))
                    }

                case Left(error) => Task.now((state, Left(error)))
              }
          }
      }
    }
  }

  private def toBuildTargetId(project: Project): bsp.BuildTargetIdentifier =
    bsp.BuildTargetIdentifier(project.bspUri)

  private def toJvmBuildTarget(project: Project): Option[bsp.JvmBuildTarget] = {
    project.compileJdkConfig.map { jdk =>
      val javaHome = bsp.Uri(jdk.javaHome.toBspUri)
      bsp.JvmBuildTarget(Some(javaHome), None)
    }
  }

  def toScalaBuildTarget(project: Project, instance: ScalaInstance): bsp.ScalaBuildTarget = {
    def toBinaryScalaVersion(version: String): String = {
      version.split('.').take(2).mkString(".")
    }

    val jars = instance.allJars.iterator.map(j => bsp.Uri(j.toPath.toUri)).toList
    val platform = project.platform match {
      case _: Platform.Jvm => bsp.ScalaPlatform.Jvm
      case _: Platform.Js => bsp.ScalaPlatform.Js
      case _: Platform.Native => bsp.ScalaPlatform.Native
    }

    bsp.ScalaBuildTarget(
      scalaOrganization = instance.organization,
      scalaVersion = instance.version,
      scalaBinaryVersion = toBinaryScalaVersion(instance.version),
      platform = platform,
      jars = jars,
      jvmBuildTarget = toJvmBuildTarget(project)
    )
  }

  def toSbtBuildTarget(sbt: Config.Sbt, scala: bsp.ScalaBuildTarget): bsp.SbtBuildTarget = {
    bsp.SbtBuildTarget(
      sbtVersion = sbt.sbtVersion,
      autoImports = sbt.autoImports,
      scalaBuildTarget = scala,
      // is threre a way to get info about parent, children ??
      parent = None,
      children = List.empty
    )
  }

  def buildTargets(): BspEndpointResponse[bsp.WorkspaceBuildTargetsResult] = {
    // need a separate block so so that state is refreshed after regenerating project data
    val refreshTask = ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      state.client.refreshProjectsCommand
        .map { command =>
          val cwd = state.build.origin
          Forker.run(cwd = cwd, command, logger, state.commonOptions).map { exitCode =>
            val status = Forker.exitStatus(exitCode)
            if (!status.isOk) {
              val message =
                s"""Refresh projects command failed with exit code $exitCode.
                   |Command run in $cwd: ${command.mkString(" ")}""".stripMargin
              (state, Left(Response.internalError(message)))
            } else {
              (state, Right(()))
            }
          }
        }
        .getOrElse(Task.now((state, Right(()))))
    }

    val buildTargetsTask = ifInitialized(None) { (state: State, _: BspServerLogger) =>
      def reportBuildError(msg: String): Unit = {
        client.notify(
          endpoints.Build.showMessage,
          ShowMessageParams(MessageType.Error, None, None, msg)
        )
        ()
      }

      Validate.validateBuildForCLICommands(state, reportBuildError(_)).flatMap { state =>
        if (state.status == ExitStatus.BuildDefinitionError)
          Task.now((state, Right(bsp.WorkspaceBuildTargetsResult(Nil))))
        else {
          val build = state.build
          val projects = build.loadedProjects.map(_.project)
          val targets = bsp.WorkspaceBuildTargetsResult(
            projects.map { p =>
              val id = toBuildTargetId(p)
              val deps = p.dependencies.iterator.flatMap(build.getProjectFor(_).toList)

              val (extra, dataKind) = (p.scalaInstance, p.sbt) match {
                case (Some(i), None) =>
                  val buildTarget = toScalaBuildTarget(p, i)
                  val encoded = writeToArray(buildTarget)
                  (Some(RawJson(encoded)), Some(bsp.BuildTargetDataKind.Scala))
                case (Some(i), Some(sbt)) =>
                  val scalaTarget = toScalaBuildTarget(p, i)
                  val sbtTarget = toSbtBuildTarget(sbt, scalaTarget)
                  val encoded = writeToArray(sbtTarget)
                  (Some(RawJson(encoded)), Some(bsp.BuildTargetDataKind.Sbt))
                case _ =>
                  None -> None
              }

              val capabilities = bsp.BuildTargetCapabilities(
                canCompile = Some(true),
                canTest = Some(true),
                canRun = Some(true),
                canDebug = Some(true)
              )
              val isJavaOnly = p.scalaInstance.isEmpty
              val languageIds =
                if (isJavaOnly) BloopBspServices.JavaOnly
                else BloopBspServices.DefaultLanguages
              bsp.BuildTarget(
                id = id,
                displayName = Some(p.name),
                baseDirectory = Some(bsp.Uri(p.baseDirectory.toBspUri)),
                tags = p.tags,
                languageIds = languageIds,
                dependencies = deps.map(toBuildTargetId).toList,
                capabilities = capabilities,
                dataKind = dataKind,
                data = extra
              )
            }
          )

          Task.now((state, Right(targets)))
        }
      }
    }

    refreshTask.flatMap {
      case Left(error) => Task.now(Left(error))
      case Right(_) => buildTargetsTask
    }
  }

  def sources(
      request: bsp.SourcesParams
  ): BspEndpointResponse[bsp.SourcesResult] = {
    def sources(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.SourcesResult] = {
      def sourceItem(s: AbsolutePath, isGenerated: Boolean): bsp.SourceItem = {
        import bsp.SourceItemKind._
        val uri = s.underlying.toUri()
        val (bspUri, kind) = if (s.exists) {
          (uri, if (s.isFile) File else Directory)
        } else {
          val fileMatcher = FileSystems.getDefault.getPathMatcher("glob:*.{scala, java}")
          if (fileMatcher.matches(s.underlying.getFileName)) (uri, File)
          // If path doesn't exist and its name doesn't look like a file, assume it's a dir
          else (new URI(uri.toString + "/"), Directory)
        }
        bsp.SourceItem(bsp.Uri(bspUri), kind, isGenerated)
      }

      val dag = Aggregate(projects.map(p => state.build.getDagFor(p._2)).toList)

      // Collect the projects' sources following the projects topological sorting, so that
      // source generators that depend on other source generators' outputs can run correctly.
      val collectSourcesTasks = Dag.dfs(dag, mode = Dag.PostOrder).map { project =>
        val unmanagedSources = project.allUnmanagedSourceFilesAndDirectories.map { sources =>
          sources.map(sourceItem(_, isGenerated = false))
        }

        val managedSources = {
          val tasks = project.sourceGenerators
            .map(state.sourceGeneratorCache.update(_, state.logger, state.commonOptions))
          Task.gatherUnordered(tasks).map(_.flatten.map(sourceItem(_, isGenerated = true)))
        }

        for {
          unmanaged <- unmanagedSources
          managed <- managedSources
        } yield (project, unmanaged ++ managed)
      }

      val projectToTarget = projects.map { case (target, project) => project -> target }.toMap
      Task.sequence(collectSourcesTasks).map { results =>
        val items = results.flatMap {
          case (project, items) =>
            val roots = project.sourceRoots.map(_.map(p => bsp.Uri(p.toBspUri)))
            projectToTarget.get(project).map(bsp.SourcesItem(_, items, roots))
        }

        (state, Right(bsp.SourcesResult(items)))
      }
    }

    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.SourcesResult(Nil))))
        case Right(mappings) => sources(mappings, state)
      }
    }
  }

  def inverseSources(
      request: bsp.InverseSourcesParams
  ): BspEndpointResponse[bsp.InverseSourcesResult] = {
    def matchesSources(document: Path, project: Project): Boolean =
      project.sources.exists(src => document.startsWith(src.underlying))
    def matchesGlobs(document: Path, project: Project): Boolean =
      project.sourcesGlobs.exists(glob => glob.matches(document))
    val document = AbsolutePath(request.textDocument.uri.toPath).underlying
    ifInitialized(None) { (state: State, _: BspServerLogger) =>
      val matchingProjects = state.build.loadedProjects
        .filter { loadedProject =>
          val project = loadedProject.project
          matchesSources(document, project) || matchesGlobs(document, project)
        }
        .map { loadedProject =>
          bsp.BuildTargetIdentifier(loadedProject.project.bspUri)
        }
      Task.now((state, Right(bsp.InverseSourcesResult(matchingProjects))))
    }
  }

  def resources(
      request: bsp.ResourcesParams
  ): BspEndpointResponse[bsp.ResourcesResult] = {
    def resources(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.ResourcesResult] = {

      val response = bsp.ResourcesResult(
        projects.iterator.map {
          case (target, project) =>
            val resources = project.runtimeResources.map { s =>
              bsp.Uri(s.toBspUri)
            }
            bsp.ResourcesItem(target, resources)
        }.toList
      )

      Task.now((state, Right(response)))
    }

    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.ResourcesResult(Nil))))
        case Right(mappings) => resources(mappings, state)
      }
    }
  }

  def outputPaths(
      request: bsp.OutputPathsParams
  ): BspEndpointResponse[bsp.OutputPathsResult] = {
    def outputPaths(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.OutputPathsResult] = {

      val response = bsp.OutputPathsResult(
        projects.iterator.map {
          case (target, project) =>
            val outputPathItems =
              List(
                bsp.OutputPathItem(bsp.Uri(project.out.toBspUri), bsp.OutputPathItemKind.Directory)
              )
            bsp.OutputPathsItem(target, outputPathItems)
        }.toList
      )

      Task.now((state, Right(response)))
    }

    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.OutputPathsResult(Nil))))
        case Right(mappings) => outputPaths(mappings, state)
      }
    }
  }

  def dependencyModules(
      request: bsp.DependencyModulesParams
  ): BspEndpointResponse[bsp.DependencyModulesResult] = {
    def modules(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.DependencyModulesResult] = {
      val response = bsp.DependencyModulesResult(
        projects.iterator.map {
          case (target, project) =>
            val modules = project.resolution.toList.flatMap { res =>
              res.modules.map { module =>
                val mavenDependencyModule = bsp.MavenDependencyModule(
                  module.organization,
                  module.name,
                  module.version,
                  module.artifacts.map(artifact =>
                    bsp.MavenDependencyModuleArtifact(
                      bsp.Uri(AbsolutePath(artifact.path).toBspUri),
                      artifact.classifier
                    )
                  ),
                  None
                )

                val encoded = writeToArray(mavenDependencyModule)
                bsp.DependencyModule(
                  module.name,
                  module.version,
                  Some(bsp.DependencyModuleDataKind.Maven),
                  Some(RawJson(encoded))
                )
              }
            }.distinct
            bsp.DependencyModulesItem(target, modules)
        }.toList
      )

      Task.now((state, Right(response)))
    }

    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.DependencyModulesResult(Nil))))
        case Right(mappings) => modules(mappings, state)
      }
    }
  }

  def dependencySources(
      request: bsp.DependencySourcesParams
  ): BspEndpointResponse[bsp.DependencySourcesResult] = {
    def sources(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.DependencySourcesResult] = {
      val response = bsp.DependencySourcesResult(
        projects.iterator.map {
          case (target, project) =>
            val sourceJars = project.resolution.toList.flatMap { res =>
              res.modules.flatMap { m =>
                m.artifacts.iterator
                  .filter(a => a.classifier.contains("sources"))
                  .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri))
                  .toList
              }
            }.distinct
            bsp.DependencySourcesItem(target, sourceJars)
        }.toList
      )

      Task.now((state, Right(response)))
    }

    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.DependencySourcesResult(Nil))))
        case Right(mappings) => sources(mappings, state)
      }
    }
  }

  private def getClasspath(state: State, project: Project): List[Uri] = {
    val uris = mutable.Map.empty[Path, bsp.Uri]
    val dag = state.build.getDagFor(project)
    val fullClasspath = project.fullClasspath(dag, state.client)
    val classpath =
      fullClasspath.map(e => uris.getOrElseUpdate(e.underlying, bsp.Uri(e.toBspUri))).toList
    classpath
  }

  private def getClassesDir(state: State, project: Project): bsp.Uri = {
    bsp.Uri(state.client.getUniqueClassesDirFor(project, forceGeneration = true).toBspUri)
  }

  def scalacOptions(
      request: bsp.ScalacOptionsParams
  ): BspEndpointResponse[bsp.ScalacOptionsResult] = {
    def scalacOptions(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.ScalacOptionsResult] = {
      val response = bsp.ScalacOptionsResult(
        projects.iterator.map {
          case (target, project) =>
            val classpath = getClasspath(state, project)
            val classesDir = getClassesDir(state, project)
            bsp.ScalacOptionsItem(
              target = target,
              options = project.scalacOptions.toList,
              classpath = classpath.map(_.value),
              classDirectory = classesDir.value
            )
        }.toList
      )
      Task.now((state, Right(response)))
    }

    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.ScalacOptionsResult(Nil))))
        case Right(mappings) => scalacOptions(mappings, state)
      }
    }
  }

  def javacOptions(
      request: bsp.JavacOptionsParams
  ): BspEndpointResponse[bsp.JavacOptionsResult] = {
    def javacOptions(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.JavacOptionsResult] = {
      val response = bsp.JavacOptionsResult(
        projects.iterator.map {
          case (target, project) =>
            val classpath = getClasspath(state, project)
            val classesDir = getClassesDir(state, project)
            bsp.JavacOptionsItem(
              target = target,
              options = project.javacOptions.toList,
              classpath = classpath.map(_.value),
              classDirectory = classesDir.value
            )
        }.toList
      )
      Task.now((state, Right(response)))
    }

    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.JavacOptionsResult(Nil))))
        case Right(mappings) => javacOptions(mappings, state)
      }
    }
  }

  val isShutdown: Promise[BspResponse[Unit]] = scala.concurrent.Promise[BspResponse[Unit]]()
  val isShutdownTask: Task[BspResponse[Unit]] = Task.fromFuture(isShutdown.future).memoize

  def shutdown(): Unit = {
    isShutdown.success(Right(()))
    callSiteState.logger.info("shutdown request received: build/shutdown")
    ()
  }

  import monix.execution.atomic.Atomic
  val exited: AtomicBoolean = Atomic(false)
  def exit(): Task[Unit] = {
    def closeServices(): Unit = {
      if (!exited.getAndSet(true)) {
        stopBspServer.success(())
      }
    }

    isShutdownTask
      .timeoutTo(
        FiniteDuration(100, TimeUnit.MILLISECONDS),
        Task.now(Left(()))
      )
      .map {
        case Left(_) => closeServices
        case Right(_) => closeServices
      }
  }
}

object BloopBspServices {
  private[bloop] val counter: AtomicInteger = new AtomicInteger(0)
  private[bloop] val DefaultLanguages = List("scala", "java")
  private[bloop] val JavaOnly = List("java")
  private[bloop] val DefaultCompileProvider = bsp.CompileProvider(DefaultLanguages)
  private[bloop] val DefaultTestProvider = bsp.TestProvider(DefaultLanguages)
  private[bloop] val DefaultRunProvider = bsp.RunProvider(DefaultLanguages)
  private[bloop] val DefaultDebugProvider = bsp.DebugProvider(DefaultLanguages)
}
