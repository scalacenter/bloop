package bloop.bsp

import java.io.InputStream
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.{FileSystems, Files, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

import bloop.io.ServerHandle
import bloop.util.JavaRuntime
import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.{CompileMode, Compiler, ScalaInstance}
import bloop.cli.{Commands, ExitStatus, Validate}
import bloop.config.Config
import bloop.dap.{DebugServer, DebuggeeRunner, StartedDebugServer}
import bloop.data.{ClientInfo, JdkConfig, Platform, Project, WorkspaceSettings}
import bloop.engine.{Aggregate, Dag, Interpreter, State}
import bloop.engine.tasks.{CompileTask, RunMode, Tasks, TestTask}
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import bloop.internal.build.BuildInfo
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspServerLogger, DebugFilter, Logger}
import bloop.reporter.{BspProjectReporter, ProblemPerPhase, ReporterConfig, ReporterInputs}
import bloop.testing.{BspLoggingEventHandler, TestInternals}
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.ScalaBuildTarget.encodeScalaBuildTarget
import ch.epfl.scala.bsp.SbtBuildTarget.encodeSbtBuildTarget
import ch.epfl.scala.bsp.{
  BuildTargetIdentifier,
  JvmEnvironmentItem,
  MessageType,
  ShowMessageParams,
  endpoints
}

import scala.meta.jsonrpc.{JsonRpcClient, Response => JsonRpcResponse, Services => JsonRpcServices}
import monix.eval.Task
import monix.reactive.Observer
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import monix.execution.atomic.AtomicBoolean

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Success, Try}
import monix.execution.Cancelable
import io.circe.{Decoder, Json}
import bloop.engine.Feedback
import monix.reactive.subjects.BehaviorSubject
import bloop.engine.tasks.compilation.CompileClientStore
import bloop.data.ClientInfo.BspClientInfo
import bloop.exec.Forker
import bloop.logging.BloopLogger

final class BloopBspServices(
    callSiteState: BspState,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath,
    stopBspServer: Cancelable,
    observer: Option[BehaviorSubject[BspState]],
    isClientConnected: AtomicBoolean,
    connectedBspClients: ConcurrentHashMap[ClientInfo.BspClientInfo, AbsolutePath],
    computationScheduler: Scheduler,
    ioScheduler: Scheduler
) {
  private implicit val debugFilter: DebugFilter = DebugFilter.Bsp
  private type ProtocolError = JsonRpcResponse.Error
  private type BspResponse[T] = Either[ProtocolError, T]

  /** The return type of every endpoint implementation */
  private type BspEndpointResponse[T] = Task[BspResponse[T]]

  /** The return type of intermediate BSP computations */
  private type BspResult[T] = Task[(BspState, BspResponse[T])]

  /** The return type of a bsp computation wrapped by `ifInitialized` */
  private type BspComputation[T] = (BspState, BspServerLogger) => BspResult[T]

  /**
   * Schedule the async response handlers to run on the default computation
   * thread pool and leave the serialization/deserialization work (bsp4s
   * library work) to the IO thread pool. This is critical for performance.
   */
  def schedule[T](t: BspEndpointResponse[T]): BspEndpointResponse[T] = {
    Task.fork(t, computationScheduler).asyncBoundary(ioScheduler)
  }

  private val backgroundDebugServers = TrieMap.empty[StartedDebugServer, Cancelable]

  // Disable ansi codes for now so that the BSP clients don't get unescaped color codes
  private val taskIdCounter: AtomicInt = AtomicInt(0)
  private val baseBspLogger =
    BspServerLogger(callSiteState.main.logger, client, taskIdCounter, false)

  final val services = JsonRpcServices
    .empty(baseBspLogger)
    .requestAsync(endpoints.Build.initialize)(p => schedule(initialize(p)))
    .notification(endpoints.Build.initialized)(initialized(_))
    .request(endpoints.Build.shutdown)(p => shutdown(p))
    .notificationAsync(endpoints.Build.exit)(p => exit(p))
    .requestAsync(endpoints.Workspace.buildTargets)(p => schedule(buildTargets(p)))
    .requestAsync(endpoints.BuildTarget.sources)(p => schedule(sources(p)))
    .requestAsync(endpoints.BuildTarget.resources)(p => schedule(resources(p)))
    .requestAsync(endpoints.BuildTarget.scalacOptions)(p => schedule(scalacOptions(p)))
    .requestAsync(endpoints.BuildTarget.compile)(p => schedule(compile(p)))
    .requestAsync(endpoints.BuildTarget.test)(p => schedule(test(p)))
    .requestAsync(endpoints.BuildTarget.run)(p => schedule(run(p)))
    .requestAsync(endpoints.BuildTarget.cleanCache)(p => schedule(clean(p)))
    .requestAsync(endpoints.BuildTarget.scalaMainClasses)(p => schedule(scalaMainClasses(p)))
    .requestAsync(endpoints.BuildTarget.scalaTestClasses)(p => schedule(scalaTestClasses(p)))
    .requestAsync(endpoints.BuildTarget.dependencySources)(p => schedule(dependencySources(p)))
    .requestAsync(endpoints.DebugSession.start)(p => schedule(startDebugSession(p)))
    .requestAsync(endpoints.BuildTarget.jvmTestEnvironment)(p => schedule(jvmTestEnvironment(p)))
    .requestAsync(endpoints.BuildTarget.jvmRunEnvironment)(p => schedule(jvmRunEnvironment(p)))
    .notificationAsync(BloopBspDefinitions.stopClientCaching)(p => stopClientCaching(p))

  // Internal state, initial value defaults to
  @volatile private var currentState: BspState = callSiteState

  /** Returns the final state after BSP commands that can be cached by bloop. */
  def stateAfterExecution: BspState = {
    // Use logger of the initial state instead of the bsp forwarder logger
    val nextState0 = currentState.updateLogger(logger = callSiteState.main.logger)
    clientInfo.future.value match {
      case Some(scala.util.Success(clientInfo)) => nextState0.updateClient(clientInfo)
      case _ => nextState0
    }
  }

  private val previouslyFailedCompilations =
    new TrieMap[(AbsolutePath, Project), Compiler.Result.Failed]()
  private def reloadState(
      config: AbsolutePath,
      clientInfo: ClientInfo,
      clientSettings: Option[WorkspaceSettings],
      bspLogger: BspServerLogger
  ): Task[BspState] = {
    val pool = currentState.pool
    val defaultOpts = currentState.commonOptions
    bspLogger.debug(s"Reloading bsp state for ${config.syntax}")

    BspState
      .loadActiveStateFor(config, clientInfo, pool, defaultOpts, bspLogger, clientSettings)
      .map { state0 =>
        /* Create a new state that has the previously compiled results in this BSP
         * client as the last compiled result available for a project. This is required
         * because in diagnostics reporting in BSP is stateful. When compilations
         * happen in other clients, the previous result does not contain the list of
         * previous problems (that tracks where we reported diagnostics) that this client
         * had and therefore we can fail to reset diagnostics. */

        val newState =
          state0
            .replacePreviousResults(previouslyFailedCompilations.toMap)
            .updateClient(clientInfo)
        currentState = newState
        newState
      }
  }

  private def saveState(state: BspState, bspLogger: BspServerLogger): Task[Unit] = {
    Task {
      state.allBuildStates.map { state =>
        val configDir = state.build.origin
        bspLogger.debug(s"Saving bsp state for ${configDir.syntax}")
        // Save the state globally so that it can be accessed by other clients
        State.stateCache.updateBuild(state)
      }
    }.flatMap(_ => publishStateInObserver(state))
  }

  // Completed whenever the initialization happens, used in `initialized`
  val clientInfo = Promise[ClientInfo.BspClientInfo]()
  val clientInfoTask = Task.fromFuture(clientInfo.future).memoize

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
            val configDir = currentState.main.build.origin
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
    val clientClassesRootDir = extraBuildParams.flatMap(
      extra => extra.clientClassesRootDir.map(dir => AbsolutePath(dir.toPath))
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
        extraBuildParams
          .flatMap(extra => extra.semanticdbVersion)
          .map { semanticDBVersion =>
            val supportedScalaVersions =
              extraBuildParams.toList.flatMap(_.supportedScalaVersions.toList.flatten)
            WorkspaceSettings(
              Some(semanticDBVersion),
              Some(supportedScalaVersions),
              currentRefreshProjectsCommand,
              currentTraceSettings
            )
          }
      }
    }

    reloadState(configDir, client, metalsSettings, bspLogger).flatMap { state =>
      callSiteState.logger.info(s"request received: build/initialize")
      clientInfo.success(client)
      connectedBspClients.put(client, configDir)
      publishStateInObserver(state.updateClient(client)).map { _ =>
        Right(
          bsp.InitializeBuildResult(
            BuildInfo.bloopName,
            BuildInfo.version,
            BuildInfo.bspVersion,
            bsp.BuildServerCapabilities(
              compileProvider = Some(BloopBspServices.DefaultCompileProvider),
              testProvider = Some(BloopBspServices.DefaultTestProvider),
              runProvider = Some(BloopBspServices.DefaultRunProvider),
              inverseSourcesProvider = Some(true),
              dependencySourcesProvider = Some(true),
              resourcesProvider = Some(true),
              buildTargetChangedProvider = Some(false),
              jvmTestEnvironmentProvider = Some(true),
              jvmRunEnvironmentProvider = Some(true)
            ),
            None
          )
        )
      }
    }
  }

  private def publishStateInObserver(state: BspState): Task[Unit] = {
    observer match {
      case None => Task.unit
      case Some(observer) => Task.fromFuture(observer.onNext(state)).map(_ => ())
    }
  }

  private def parseBloopExtraParams(data: Option[Json]): Option[BloopExtraBuildParams] = {
    data.flatMap { json =>
      BloopExtraBuildParams.decoder.decodeJson(json) match {
        case Right(bloopParams) =>
          Some(bloopParams)
        case Left(failure) =>
          callSiteState.logger.warn(
            s"Unexpected error decoding bloop-specific initialize params: ${failure.message}"
          )
          None
      }
    }
  }

  val isInitialized = scala.concurrent.Promise[BspResponse[Unit]]()
  val isInitializedTask = Task.fromFuture(isInitialized.future).memoize
  def initialized(
      initializedBuildParams: bsp.InitializedBuildParams
  ): Unit = {
    isInitialized.success(Right(()))
    callSiteState.logger.info("BSP initialization handshake complete.")
  }

  def ifInitialized[T](
      originId: Option[String]
  )(compute: BspComputation[T]): BspEndpointResponse[T] = {
    val bspLogger = baseBspLogger.withOriginId(originId)
    // Give a time window for `isInitialized` to complete, otherwise assume it didn't happen
    isInitializedTask
      .flatMap(response => clientInfoTask.map(clientInfo => response.map(_ => clientInfo)))
      .timeoutTo(
        FiniteDuration(1, TimeUnit.SECONDS),
        Task.now(Left(JsonRpcResponse.invalidRequest("The session has not been initialized.")))
      )
      .flatMap {
        case Left(e) => Task.now(Left(e))
        case Right(clientInfo) =>
          reloadState(currentState.main.build.origin, clientInfo, None, bspLogger).flatMap {
            state =>
              compute(state, bspLogger).flatMap {
                case (state, e) => saveState(state, bspLogger).map(_ => e)
              }
          }
      }
  }

  case class ProjectMapping(
      id: bsp.BuildTargetIdentifier,
      project: Project
  )
  case class MappingGroup(
      buildState: State,
      mappings: List[ProjectMapping],
      isMain: Boolean
  )

  private def projectMappingFromUri(
      target: bsp.BuildTargetIdentifier,
      bspState: BspState
  ): Either[String, (State, ProjectMapping)] = {
    val uri = target.uri

    ProjectUris.parseUri(uri.value) match {
      case Left(errorMsg) => Left(errorMsg)
      case Right(parsed) =>
        val out =
          for {
            st <- bspState.buildStateByBaseDirectory(parsed.path)
            project <- st.build.getProjectFor(parsed.name)
          } yield (st, ProjectMapping(target, project))

        out.toRight(s"No project associated with $uri")
    }
  }

  private def mapToGroups(
      targets: Seq[bsp.BuildTargetIdentifier],
      bspState: BspState
  ): Either[String, List[MappingGroup]] = {
    if (targets.isEmpty) {
      Left("Empty build targets. Expected at least one build target identifier.")
    } else {

      val zero: Either[String, Map[AbsolutePath, (State, List[ProjectMapping])]] = Right(Map.empty)
      targets
        .foldLeft(zero) { (acc, t) =>
          acc.flatMap(pathToMappings => {
            projectMappingFromUri(t, bspState)
              .map {
                case (st, pm) =>
                  val path = st.build.origin
                  val (_, mappingsToSt) =
                    pathToMappings.getOrElse(path, (st, List.empty[ProjectMapping]))
                  val next = pm :: mappingsToSt

                  pathToMappings.updated(path, (st, next))
              }
          })
        }
        .map { groups =>
          groups.values.map {
            case (st, mappings) =>
              val isMain = st.build.origin == bspState.main.build.origin
              MappingGroup(st, mappings, isMain)
          }.toList
        }
    }
  }

  private def toMainOnlyMappingGroup(
      targets: Seq[bsp.BuildTargetIdentifier],
      bspState: BspState
  ): Either[String, Option[MappingGroup]] =
    mapToGroups(targets, bspState).map(_.find(_.isMain))

  /**
   * Keep track of those projects that were compiled at least once so that we can
   * decide to enable fresh reporting for projects that are compiled for the first time.
   *
   * Required by https://github.com/scalacenter/bloop/issues/726
   */
  private val compiledTargetsAtLeastOnce = new TrieMap[bsp.BuildTargetIdentifier, Boolean]()

  private val originToCompileStores = new TrieMap[String, CompileClientStore.ConcurrentStore]()

  def stopClientCaching(params: BloopBspDefinitions.StopClientCachingParams): Task[Unit] = {
    Task.fork(Task.eval { originToCompileStores.remove(params.originId); () })
  }

  def compileGroups(
      groups: List[MappingGroup],
      bspState: BspState,
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
    val groupsCompilation = groups
      .sortBy(_.buildState.build.origin.toString)
      .map(
        group =>
          compileProjectGroup(
            group,
            compileArgs,
            originId,
            cancelCompilation,
            logger
          ).map(newState => (group.buildState, newState))
      )

    Task
      .sequence(groupsCompilation)
      .map(results => {

        val init = (List.empty[State], List.empty[String])
        val (updatedStates, errors) = results.foldLeft(init) {
          case ((statesAcc, errorsAcc), (prevState, updatedState)) =>
            val compiledResults = prevState.results.diffLatest(updatedState.results)
            val errorMsgs = compiledResults.flatMap {
              case (p, result) =>
                result match {
                  case Compiler.Result.Empty => Nil
                  case Compiler.Result.Blocked(_) => Nil
                  case Compiler.Result.Success(_, _, _, _, _, _, _) =>
                    previouslyFailedCompilations.remove((updatedState.build.origin, p))
                    Nil
                  case Compiler.Result.GlobalError(problem) => List(problem)
                  case Compiler.Result.Cancelled(problems, elapsed, _) =>
                    List(reportError(p, problems, elapsed))
                  case f @ Compiler.Result.Failed(problems, t, elapsed, _) =>
                    previouslyFailedCompilations.put((updatedState.build.origin, p), f)
                    val acc = List(reportError(p, problems, elapsed))
                    t match {
                      case Some(t) =>
                        s"Bloop error when compiling ${p.name}: '${t.getMessage}'" :: acc
                      case None => acc
                    }
                }
            }

            val nextErrors = errorsAcc ++ errorMsgs
            (updatedState :: statesAcc, nextErrors)
        }

        val newState = bspState.updateBuildStates(updatedStates)
        val response: Either[ProtocolError, bsp.CompileResult] = {
          if (cancelCompilation.isCompleted)
            Right(bsp.CompileResult(originId, bsp.StatusCode.Cancelled, None, None))
          else {
            errors match {
              case Nil => Right(bsp.CompileResult(originId, bsp.StatusCode.Ok, None, None))
              case xs => Right(bsp.CompileResult(originId, bsp.StatusCode.Error, None, None))
            }
          }
        }

        (newState, response)
      })
  }

  def compileProjectGroup(
      group: MappingGroup,
      compileArgs: List[String],
      originId: Option[String],
      cancelCompilation: Promise[Unit],
      logger: BspServerLogger
  ): Task[State] = {

    val isPipeline = compileArgs.exists(_ == "--pipeline")
    def compile(projects: List[Project]): Task[State] = {
      val config = ReporterConfig.defaultFormat.copy(reverseOrder = false)

      val isSbtClient = group.buildState.client match {
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

      val dag = Aggregate(projects.map(p => group.buildState.build.getDagFor(p)))
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
        group.buildState,
        dag,
        createReporter,
        isPipeline,
        false,
        cancelCompilation,
        store,
        logger
      )
    }

    val projects: List[Project] = {
      val projects0 =
        Dag.reduce(group.buildState.build.dags, group.mappings.map(_.project).toSet).toList
      if (!compileArgs.exists(_ == "--cascade")) projects0
      else Dag.inverseDependencies(group.buildState.build.dags, projects0).reduced
    }

    compile(projects)
  }

  def compile(params: bsp.CompileParams): BspEndpointResponse[bsp.CompileResult] = {
    ifInitialized(params.originId) { (state: BspState, logger0: BspServerLogger) =>
      mapToGroups(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger0.error(error)
          Task.now((state, Right(bsp.CompileResult(None, bsp.StatusCode.Error, None, None))))
        case Right(groups) =>
          val compileArgs = params.arguments.getOrElse(Nil)
          val isVerbose = compileArgs.exists(_ == "--verbose")
          val logger = logger0.asBspServerVerbose
          compileGroups(groups, state, compileArgs, params.originId, logger)
      }
    }
  }

  def clean(params: bsp.CleanCacheParams): BspEndpointResponse[bsp.CleanCacheResult] = {
    ifInitialized(None) { (state: BspState, logger: BspServerLogger) =>
      mapToGroups(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          val msg = s"Couldn't map all targets to clean to projects in the build: $error"
          Task.now((state, Right(bsp.CleanCacheResult(Some(msg), cleaned = false))))
        case Right(groups) =>
          val groupsClean = groups.map(group => {
            val projectsToClean = group.mappings.map(_.project)
            Tasks
              .clean(group.buildState, projectsToClean, includeDeps = false)
              .materialize
              .map(r => r -> group.buildState)
          })

          Task
            .gatherUnordered(groupsClean)
            .map(results => {
              val init = (List.empty[State], List.empty[String])
              val (states, errors) = results.foldLeft(init) {
                case ((stAcc, errAcc), (Success(newSt), _)) => (newSt :: stAcc, errAcc)
                case ((stAcc, errAcc), (Failure(e), st)) =>
                  val t = BloopLogger.prettyPrintException(e)
                  val msg =
                    s"Unexpected error when cleaning build targets!${System.lineSeparator}$t"
                  (st :: stAcc, msg :: errAcc)
              }

              val newState = state.updateBuildStates(states)
              val errorsResult =
                if (errors.nonEmpty) Some(errors.mkString(System.lineSeparator)) else None
              val isCleaned = errorsResult.isEmpty
              newState -> Right(bsp.CleanCacheResult(errorsResult, cleaned = isCleaned))
            })
      }
    }
  }

  def scalaTestClasses(
      params: bsp.ScalaTestClassesParams
  ): BspEndpointResponse[bsp.ScalaTestClassesResult] = {
    ifInitialized(params.originId) { (state: BspState, logger: BspServerLogger) =>
      toMainOnlyMappingGroup(params.targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Right(bsp.ScalaTestClassesResult(Nil))))

        case Right(None) =>
          Task.now((state, Right(bsp.ScalaTestClassesResult(Nil))))
        case Right(Some(group)) =>
          val subTasks = for {
            mapping <- group.mappings
            task = TestTask.findFullyQualifiedTestNames(mapping.project, group.buildState)
            item = task.map(classes => bsp.ScalaTestClassesItem(mapping.id, classes))
          } yield item

          for {
            items <- Task.sequence(subTasks)
            result = new bsp.ScalaTestClassesResult(items)
          } yield (state, Right(result))
      }
    }
  }

  def startDebugSession(
      params: bsp.DebugSessionParams
  ): BspEndpointResponse[bsp.DebugSessionAddress] = {
    def inferDebuggeeRunner(
        projects: Seq[Project],
        state: State
    ): BspResponse[DebuggeeRunner] = {
      def convert[A: Decoder](
          f: A => Either[String, DebuggeeRunner]
      ): Either[ProtocolError, DebuggeeRunner] = {
        params.data.as[A] match {
          case Left(error) =>
            Left(JsonRpcResponse.invalidRequest(error.getMessage()))
          case Right(params) =>
            f(params) match {
              case Right(adapter) => Right(adapter)
              case Left(error) => Left(JsonRpcResponse.invalidRequest(error))
            }
        }
      }

      params.dataKind match {
        case bsp.DebugSessionParamsDataKind.ScalaMainClass =>
          convert[bsp.ScalaMainClass](main => DebuggeeRunner.forMainClass(projects, main, state))
        case bsp.DebugSessionParamsDataKind.ScalaTestSuites =>
          convert[List[String]](filters => DebuggeeRunner.forTestSuite(projects, filters, state))
        case dataKind => Left(JsonRpcResponse.invalidRequest(s"Unsupported data kind: $dataKind"))
      }
    }

    ifInitialized(None) { (state, logger) =>
      JavaRuntime.loadJavaDebugInterface match {
        case Failure(exception) =>
          val message = JavaRuntime.current match {
            case JavaRuntime.JDK => Feedback.detectedJdkWithoutJDI(exception)
            case JavaRuntime.JRE => Feedback.detectedUnsupportedJreForDebugging(exception)
          }
          Task.now((state, Left(JsonRpcResponse.internalError(message))))

        case Success(_) =>
          toMainOnlyMappingGroup(params.targets, state) match {
            case Left(error) =>
              // Log the mapping error to the user via a log event + an error status code
              logger.error(error)
              Task.now((state, Left(JsonRpcResponse.invalidRequest(error))))
            case Right(None) =>
              val error = "Debug doesn't work for requested targets"
              logger.error(error)
              Task.now((state, Left(JsonRpcResponse.invalidRequest(error))))
            case Right(Some(group)) =>
              // FIXME: Add origin id to DAP request
              compileGroups(List(group), state, Nil, None, logger).flatMap {
                case (state, Left(error)) =>
                  Task.now((state, Left(error)))
                case (state, Right(result)) if result.statusCode != bsp.StatusCode.Ok =>
                  Task.now(
                    (state, Left(JsonRpcResponse.internalError("Compilation not successful")))
                  )
                case (state, Right(_)) =>
                  val projects = group.mappings.map(_.project)
                  inferDebuggeeRunner(projects, group.buildState) match {
                    case Right(runner) =>
                      val startedServer = DebugServer.start(runner, logger, ioScheduler)
                      val listenAndUnsubscribe = startedServer.listen
                        .runOnComplete(_ => backgroundDebugServers -= startedServer)(ioScheduler)
                      backgroundDebugServers += startedServer -> listenAndUnsubscribe

                      startedServer.address.map {
                        case Some(uri) => (state, Right(new bsp.DebugSessionAddress(uri.toString)))
                        case None =>
                          val error = JsonRpcResponse.internalError("Failed to start debug server")
                          (state, Left(error))
                      }

                    case Left(error) =>
                      Task.now((state, Left(error)))
                  }
              }
          }
      }
    }
  }

  def test(params: bsp.TestParams): BspEndpointResponse[bsp.TestResult] = {
    def test(
        id: BuildTargetIdentifier,
        project: Project,
        state: State
    ): Task[State] = {
      val testFilter = TestInternals.parseFilters(Nil) // Don't support test only for now
      val handler = new BspLoggingEventHandler(id, state.logger, client)
      Tasks.test(state, List(project), Nil, testFilter, handler, mode = RunMode.Normal)
    }

    val originId = params.originId
    ifInitialized(originId) { (state: BspState, logger0: BspServerLogger) =>
      toMainOnlyMappingGroup(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger0.error(error)
          Task.now((state, Right(bsp.TestResult(originId, bsp.StatusCode.Error, None, None))))
        case Right(None) =>
          Task.now((state, Right(bsp.TestResult(originId, bsp.StatusCode.Error, None, None))))
        case Right(Some(group)) =>
          val args = params.arguments.getOrElse(Nil)
          val isVerbose = args.exists(_ == "--verbose")
          val logger = logger0.asBspServerVerbose
          compileGroups(List(group), state, args, originId, logger).flatMap {
            case (newState, compileResult) =>
              compileResult match {
                case Right(result) =>
                  val sequentialTestExecution = group.mappings.foldLeft(Task.now(newState.main)) {
                    case (taskState, mapping) =>
                      taskState.flatMap(
                        state => test(mapping.id, mapping.project, group.buildState)
                      )
                  }

                  sequentialTestExecution.materialize.map(_.toEither).map {
                    case Right(newMainBuildState) =>
                      (
                        newState.copy(main = newMainBuildState),
                        Right(bsp.TestResult(originId, bsp.StatusCode.Ok, None, None))
                      )
                    case Left(e) =>
                      //(newState, Right(bsp.TestResult(None, bsp.StatusCode.Error, None)))
                      val errorMessage =
                        JsonRpcResponse.internalError(s"Failed test execution: ${e.getMessage}")
                      (newState, Left(errorMessage))
                  }

                case Left(error) => Task.now((newState, Left(error)))
              }
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
    ifInitialized(None) { (state: BspState, logger: BspServerLogger) =>
      mapToGroups(targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Left(JsonRpcResponse.invalidRequest(error))))

        case Right(groups) =>
          val environmentEntries = (for {
            group <- groups
            mapping <- group.mappings
            project = mapping.project
            dag = group.buildState.build.getDagFor(project)
            fullClasspath = project.fullRuntimeClasspath(dag, state.client).map(_.toBspUri.toString)
            environmentVariables = state.commonOptions.env.toMap
            workingDirectory = project.workingDirectory.toString
            javaOptions <- project.platform match {
              case Platform.Jvm(config, _, _, _, _) => Some(config.javaOptions.toList)
              case _ => None
            }
          } yield {
            bsp.JvmEnvironmentItem(
              mapping.id,
              fullClasspath.toList,
              javaOptions,
              workingDirectory,
              environmentVariables
            )
          }).toList
          Task.now((state, Right(environmentEntries)))
      }
    }
  }

  def scalaMainClasses(
      params: bsp.ScalaMainClassesParams
  ): BspEndpointResponse[bsp.ScalaMainClassesResult] = {
    def findMainClasses(buildState: State, project: Project): List[bsp.ScalaMainClass] =
      for {
        className <- Tasks.findMainClasses(buildState, project)
      } yield bsp.ScalaMainClass(className, Nil, Nil)

    ifInitialized(params.originId) { (state: BspState, logger: BspServerLogger) =>
      toMainOnlyMappingGroup(params.targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Right(bsp.ScalaMainClassesResult(Nil))))
        case Right(None) =>
          Task.now((state, Right(bsp.ScalaMainClassesResult(Nil))))
        case Right(Some(group)) =>
          val items = for {
            mapping <- group.mappings
            mainClasses = findMainClasses(group.buildState, mapping.project)
          } yield bsp.ScalaMainClassesItem(mapping.id, mainClasses)

          val result = new bsp.ScalaMainClassesResult(items)
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
              json.as[bsp.ScalaMainClass]
          }
        case Some(kind) =>
          Left(new IllegalArgumentException(s"Unsupported data kind: $kind"))
        case None =>
          val cmd = Commands.Run(List(project.name))
          Interpreter.getMainClass(state, project, cmd.main) match {
            case Right(name) =>
              Right(new bsp.ScalaMainClass(name, cmd.args, Nil))
            case Left(_) =>
              Left(new IllegalStateException(s"Main class for project $project not found"))
          }
      }
    }

    def run(
        id: BuildTargetIdentifier,
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
            case Platform.Jvm(config0, _, _, _, _) =>
              val mainArgs = mainClass.arguments.toArray
              val config = JdkConfig(config0.javaHome, config0.javaOptions ++ mainClass.jvmOptions)
              Tasks.runJVM(
                state,
                project,
                config,
                cwd,
                mainClass.`class`,
                mainArgs,
                skipJargs = false,
                RunMode.Normal
              )
            case platform @ Platform.Native(config, _, _) =>
              val cmd = Commands.Run(List(project.name))
              val target = ScalaNativeToolchain.linkTargetFrom(project, config)
              linkMainWithNative(cmd, project, state, mainClass.`class`, target, platform)
                .flatMap { state =>
                  val args = (target.syntax +: cmd.args).toArray
                  if (!state.status.isOk) Task.now(state)
                  else Tasks.runNativeOrJs(state, project, cwd, mainClass.`class`, args)
                }
            case platform @ Platform.Js(config, _, _) =>
              val cmd = Commands.Run(List(project.name))
              val target = ScalaJsToolchain.linkTargetFrom(project, config)
              linkMainWithJs(cmd, project, state, mainClass.`class`, target, platform)
                .flatMap { state =>
                  // We use node to run the program (is this a special case?)
                  val args = ("node" +: target.syntax +: cmd.args).toArray
                  if (!state.status.isOk) Task.now(state)
                  else Tasks.runNativeOrJs(state, project, cwd, mainClass.`class`, args)
                }
          }
      }
    }

    val originId = params.originId
    ifInitialized(originId) { (state: BspState, logger0: BspServerLogger) =>
      val mappingWithBuildState = toMainOnlyMappingGroup(Seq(params.target), state)
        .flatMap { maybeGroup =>
          val findTarget = maybeGroup.flatMap(
            group =>
              group.mappings.find(_.id == params.target).map(mapping => group.buildState -> mapping)
          )
          findTarget.toRight(s"Internal Error. Couldn't find state for ${params.target}")
        }
      mappingWithBuildState match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger0.error(error)
          Task.now((state, Right(bsp.RunResult(originId, bsp.StatusCode.Error))))
        case Right((buildState, mapping)) =>
          val args = params.arguments.getOrElse(Nil)
          val isVerbose = args.exists(_ == "--verbose")
          val logger = logger0.asBspServerVerbose

          val groupForCompile = MappingGroup(buildState, List(mapping), true)
          compileGroups(List(groupForCompile), state, args, originId, logger).flatMap {
            case (newState, compileResult) =>
              compileResult match {
                case Right(result) =>
                  var isCancelled: Boolean = false
                  run(mapping.id, mapping.project, newState.main)
                    .doOnCancel(Task { isCancelled = true; () })
                    .materialize
                    .map(_.toEither)
                    .map {
                      case Left(e) =>
                        val errorMsg =
                          JsonRpcResponse.internalError(s"Failed test execution: ${e.getMessage}")
                        (newState, Left(errorMsg))
                      case Right(state) =>
                        val status = {
                          val exitStatus = state.status
                          if (isCancelled) bsp.StatusCode.Cancelled
                          else if (exitStatus.isOk) bsp.StatusCode.Ok
                          else bsp.StatusCode.Error
                        }
                        (newState.copy(main = state), Right(bsp.RunResult(originId, status)))
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
    project.jdkConfig.map { jdk =>
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

  def buildTargets(
      request: bsp.WorkspaceBuildTargetsRequest
  ): BspEndpointResponse[bsp.WorkspaceBuildTargetsResult] = {
    // need a separate block so so that state is refreshed after regenerating project data
    val refreshTask = ifInitialized(None) { (state: BspState, logger: BspServerLogger) =>
      state.client.refreshProjectsCommand match {
        case None => Task.now((state, Right(())))
        case Some(command) =>
          val refreshAll = state.allBuildStates.map { buildState =>
            val cwd = buildState.build.origin
            Forker.run(cwd = cwd, command, logger, buildState.commonOptions).map { exitCode =>
              val status = Forker.exitStatus(exitCode)
              if (!status.isOk) {
                val message =
                  s"""Refresh projects command failed with exit code $exitCode.
                     |Command run in $cwd: ${command.mkString(" ")}""".stripMargin
                Left(message)
              } else {
                Right(())
              }
            }
          }
          Task.gatherUnordered(refreshAll).map { results =>
            val errors = results.foldLeft(List.empty[String]) {
              case (acc, Right(_)) => acc
              case (acc, Left(err)) => err :: acc
            }

            if (errors.nonEmpty) {
              (state, Left(JsonRpcResponse.internalError(errors.mkString("; "))))
            } else {
              (state, Right(()))
            }
          }
      }
    }

    val buildTargetsTask = ifInitialized(None) { (state: BspState, _: BspServerLogger) =>
      def reportBuildError(msg: String): Unit = {
        endpoints.Build.showMessage.notify(
          ShowMessageParams(MessageType.Error, None, None, msg)
        )(client)
        ()
      }

      val targetsForStates = state.allBuildStates.map { buildState =>
        Validate.validateBuildForCLICommands(buildState, reportBuildError).map { buildState =>
          if (buildState.status == ExitStatus.BuildDefinitionError)
            Nil
          else {
            val build = buildState.build
            val projects = build.loadedProjects.map(_.project)
            projects.map { p =>
              val id = toBuildTargetId(p)
              val deps = p.dependencies.iterator.flatMap(build.getProjectFor(_).toList)

              val (extra, dataKind) = (p.scalaInstance, p.sbt) match {
                case (Some(i), None) =>
                  Some(encodeScalaBuildTarget(toScalaBuildTarget(p, i))) -> Some(
                    bsp.BuildTargetDataKind.Scala
                  )
                case (Some(i), Some(sbt)) =>
                  val scalaTarget = toScalaBuildTarget(p, i)
                  val sbtTarget = toSbtBuildTarget(sbt, scalaTarget)
                  Some(encodeSbtBuildTarget(sbtTarget)) -> Some(bsp.BuildTargetDataKind.Sbt)
                case _ =>
                  None -> None
              }

              val capabilities = bsp.BuildTargetCapabilities(
                canCompile = true,
                canTest = true,
                canRun = true
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
          }
        }
      }

      Task
        .sequence(targetsForStates)
        .map(_.flatten)
        .map(allTargets => (state, Right(bsp.WorkspaceBuildTargetsResult(allTargets))))
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
        projects: Seq[ProjectMapping]
    ): Task[List[bsp.SourcesItem]] = {
      val sourcesItems = projects.map { mapping =>
        val project = mapping.project
        project.allSourceFilesAndDirectories.map { sources =>
          val items = sources.map { s =>
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
            // TODO(jvican): Don't default on false for generated, add this info to JSON fields
            bsp.SourceItem(bsp.Uri(bspUri), kind, false)
          }
          val roots = project.sourceRoots.map(_.map(p => bsp.Uri(p.toBspUri)))
          bsp.SourcesItem(mapping.id, items, roots)
        }
      }.toList

      Task.sequence(sourcesItems)
    }

    ifInitialized(None) { (state: BspState, logger: BspServerLogger) =>
      mapToGroups(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.SourcesResult(Nil))))
        case Right(groups) =>
          val allItems = Task.sequence(groups.map(group => sources(group.mappings))).map(_.flatten)
          allItems.map(items => (state, Right(bsp.SourcesResult(items))))
      }
    }
  }

  def resources(
      request: bsp.ResourcesParams
  ): BspEndpointResponse[bsp.ResourcesResult] = {
    def resources(
        projects: Seq[ProjectMapping]
    ): List[bsp.ResourcesItem] = {
      projects.map { mapping =>
        val resources = mapping.project.runtimeResources.flatMap { s =>
          if (s.exists) {
            val resources = Files.walk(s.underlying).collect(Collectors.toList[Path]).asScala
            resources.map(r => bsp.Uri(r.toUri()))
          } else {
            Seq.empty
          }
        }
        bsp.ResourcesItem(mapping.id, resources)
      }.toList
    }

    ifInitialized(None) { (state: BspState, logger: BspServerLogger) =>
      mapToGroups(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.ResourcesResult(Nil))))
        case Right(groups) =>
          val allItems = groups.flatMap(group => resources(group.mappings))
          Task.now((state, Right(bsp.ResourcesResult(allItems))))
      }
    }
  }

  def dependencySources(
      request: bsp.DependencySourcesParams
  ): BspEndpointResponse[bsp.DependencySourcesResult] = {
    def sources(
        mappings: Seq[ProjectMapping]
    ): List[bsp.DependencySourcesItem] = {
      mappings.map { mapping =>
        val sourceJars = mapping.project.resolution.toList.flatMap { res =>
          res.modules.flatMap { m =>
            m.artifacts.iterator
              .filter(a => a.classifier.toList.contains("sources"))
              .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri))
              .toList
          }
        }.distinct
        bsp.DependencySourcesItem(mapping.id, sourceJars)
      }.toList
    }

    ifInitialized(None) { (state: BspState, logger: BspServerLogger) =>
      mapToGroups(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.DependencySourcesResult(Nil))))
        case Right(groups) =>
          val items = groups.flatMap(group => sources(group.mappings))
          Task.now((state, Right(bsp.DependencySourcesResult(items))))
      }
    }
  }

  def scalacOptions(
      request: bsp.ScalacOptionsParams
  ): BspEndpointResponse[bsp.ScalacOptionsResult] = {
    def scalacOptions(
        projects: Seq[ProjectMapping],
        state: State
    ): List[bsp.ScalacOptionsItem] = {
      val uris = mutable.Map.empty[Path, bsp.Uri]
      projects.map { mapping =>
        val dag = state.build.getDagFor(mapping.project)
        val fullClasspath = mapping.project.fullClasspath(dag, state.client)
        val classpath =
          fullClasspath.map(e => uris.getOrElseUpdate(e.underlying, bsp.Uri(e.toBspUri))).toList
        val classesDir =
          state.client.getUniqueClassesDirFor(mapping.project, forceGeneration = true).toBspUri
        bsp.ScalacOptionsItem(
          target = mapping.id,
          options = mapping.project.scalacOptions.toList,
          classpath = classpath,
          classDirectory = bsp.Uri(classesDir)
        )
      }.toList
    }

    ifInitialized(None) { (state: BspState, logger: BspServerLogger) =>
      mapToGroups(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          // TODO(jvican): Add status code to scalac options result
          Task.now((state, Right(bsp.ScalacOptionsResult(Nil))))
        case Right(groups) =>
          val allItems = groups.flatMap(group => scalacOptions(group.mappings, group.buildState))
          Task.now((state, Right(bsp.ScalacOptionsResult(allItems))))
      }
    }
  }

  val isShutdown = scala.concurrent.Promise[BspResponse[Unit]]()
  val isShutdownTask = Task.fromFuture(isShutdown.future).memoize
  def shutdown(shutdown: bsp.Shutdown): Unit = {
    isShutdown.success(Right(()))
    callSiteState.logger.info("shutdown request received: build/shutdown")
    ()
  }

  import monix.execution.atomic.Atomic
  val exited = Atomic(false)
  def exit(shutdown: bsp.Exit): Task[Unit] = {
    def closeServices(): Unit = {
      if (!exited.getAndSet(true)) {
        stopBspServer.cancel()
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
}
