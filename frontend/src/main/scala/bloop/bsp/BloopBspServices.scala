package bloop.bsp

import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.nio.file.{Files, FileSystems, Path}
import java.util.concurrent.ConcurrentHashMap
import java.util.stream.Collectors

import bloop.io.ServerHandle
import bloop.data.WorkspaceSettings
import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.{CompileMode, Compiler, ScalaInstance}
import bloop.cli.{Commands, ExitStatus, Validate}
import bloop.dap.{DebugServer, DebuggeeRunner, StartedDebugServer}
import bloop.data.{ClientInfo, Platform, Project}
import bloop.engine.{State, Aggregate, Dag, Interpreter}
import bloop.engine.tasks.{CompileTask, Tasks, TestTask, RunMode}
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import bloop.exec.JavaEnv
import bloop.internal.build.BuildInfo
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspServerLogger, DebugFilter, Logger}
import bloop.reporter.{BspProjectReporter, ProblemPerPhase, ReporterConfig, ReporterInputs}
import bloop.testing.{BspLoggingEventHandler, TestInternals}

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.ScalaBuildTarget.encodeScalaBuildTarget
import ch.epfl.scala.bsp.{BuildTargetIdentifier, MessageType, ShowMessageParams, endpoints}

import scala.meta.jsonrpc.{JsonRpcClient, Response => JsonRpcResponse, Services => JsonRpcServices}
import monix.eval.Task
import monix.reactive.Observer
import monix.execution.Scheduler
import monix.execution.atomic.AtomicInt
import monix.execution.atomic.AtomicBoolean

import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Success
import scala.util.Failure

import monix.execution.Cancelable
import io.circe.{Decoder, Json}
import bloop.engine.Feedback

final class BloopBspServices(
    callSiteState: State,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath,
    stopBspServer: Cancelable,
    observer: Option[Observer.Sync[State]],
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

  /** The return type of intermediate BSP compputations */
  private type BspResult[T] = Task[(State, BspResponse[T])]

  /** The return type of a bsp computation wrapped by `ifInitialized` */
  private type BspComputation[T] = (State, BspServerLogger) => BspResult[T]

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
  private val baseBspLogger = BspServerLogger(callSiteState, client, taskIdCounter, false)

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
    .requestAsync(endpoints.BuildTarget.scalaMainClasses)(p => schedule(scalaMainClasses(p)))
    .requestAsync(endpoints.BuildTarget.scalaTestClasses)(p => schedule(scalaTestClasses(p)))
    .requestAsync(endpoints.BuildTarget.dependencySources)(p => schedule(dependencySources(p)))
    .requestAsync(endpoints.DebugSession.start)(p => schedule(startDebugSession(p)))

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
      observer.foreach(_.onNext(state))
      ()
    }
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
    val extraBuildParams = parseClientClassesRootDir(params.data)
    val clientClassesRootDir = extraBuildParams.flatMap(
      extra => extra.clientClassesRootDir.map(dir => AbsolutePath(dir.toPath))
    )
    val client = ClientInfo.BspClientInfo(
      params.displayName,
      params.version,
      params.bspVersion,
      clientClassesRootDir,
      () => isClientConnected.get
    )

    /**
     * A Metals BSP client enables a special transformation of a build via the
     * workspace settings. These workspace settings contains all of the
     * information required by bloop to enable Metals-specific settings in
     * every project of a build so that users from different build tools don't
     * need to manually enable these in their build.
     */
    val metalsSettings = {
      if (!params.displayName.contains("Metals")) {
        None
      } else {
        extraBuildParams
          .flatMap(extra => extra.semanticdbVersion)
          .map { semanticDBVersion =>
            val supportedScalaVersions =
              extraBuildParams.toList.flatMap(_.supportedScalaVersions.toList.flatten)
            WorkspaceSettings(
              semanticDBVersion,
              supportedScalaVersions
            )
          }
      }
    }

    reloadState(configDir, client, metalsSettings, bspLogger).map { state =>
      callSiteState.logger.info(s"request received: build/initialize")
      clientInfo.success(client)
      connectedBspClients.put(client, configDir)
      observer.foreach(_.onNext(state.copy(client = client)))
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
            buildTargetChangedProvider = Some(false)
          ),
          None
        )
      )
    }
  }

  private def parseClientClassesRootDir(data: Option[Json]): Option[BloopExtraBuildParams] = {
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

    val pipeline = compileArgs.exists(_ == "--pipeline")
    def compile(projects: List[Project]): Task[State] = {
      val cwd = state.build.origin.getParent
      val config = ReporterConfig.defaultFormat.copy(reverseOrder = false)

      val createReporter = (inputs: ReporterInputs[BspServerLogger]) => {
        val btid = bsp.BuildTargetIdentifier(inputs.project.bspUri)
        val reportAllPreviousProblems = {
          compiledTargetsAtLeastOnce.putIfAbsent(btid, true) match {
            case Some(_) => false
            case None => true
          }
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
      CompileTask.compile(state, dag, createReporter, pipeline, false, cancelCompilation, logger)
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
            case Compiler.Result.GlobalError(problem) => List(problem)
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

      val response: Either[ProtocolError, bsp.CompileResult] = {
        if (cancelCompilation.isCompleted)
          Right(bsp.CompileResult(originId, bsp.StatusCode.Cancelled, None, None))
        else {
          errorMsgs match {
            case Nil => Right(bsp.CompileResult(originId, bsp.StatusCode.Ok, None, None))
            case xs => Right(bsp.CompileResult(originId, bsp.StatusCode.Error, None, None))
          }
        }
      }

      (newState, response)
    }
  }

  def compile(params: bsp.CompileParams): BspEndpointResponse[bsp.CompileResult] = {
    ifInitialized(params.originId) { (state: State, logger: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.CompileResult(None, bsp.StatusCode.Error, None, None))))
        case Right(mappings) =>
          val compileArgs = params.arguments.getOrElse(Nil)
          compileProjects(mappings, state, compileArgs, params.originId, logger)
      }
    }
  }

  def scalaTestClasses(
      params: bsp.ScalaTestClassesParams
  ): BspEndpointResponse[bsp.ScalaTestClassesResult] = {
    ifInitialized(params.originId) { (state: State, logger: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Right(bsp.ScalaTestClassesResult(Nil))))

        case Right(projects) =>
          val subTasks = for {
            (id, project) <- projects.toList
            task = TestTask.findFullyQualifiedTestNames(project, state)
            item = task.map(classes => bsp.ScalaTestClassesItem(id, classes))
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
      JavaEnv.loadJavaDebugInterface match {
        case Failure(exception) =>
          val message = JavaEnv.detectRuntime match {
            case JavaEnv.JDK => Feedback.detectedJdkWithoutJDI(exception)
            case JavaEnv.JRE => Feedback.detectedUnsupportedJreForDebugging(exception)
          }
          Task.now((state, Left(JsonRpcResponse.internalError(message))))

        case Success(_) =>
          mapToProjects(params.targets, state) match {
            case Left(error) =>
              // Log the mapping error to the user via a log event + an error status code
              logger.error(error)
              Task.now((state, Left(JsonRpcResponse.invalidRequest(error))))
            case Right(mappings) =>
              // FIXME: Add origin id to DAP request
              compileProjects(mappings, state, Nil, None, logger).flatMap {
                case (state, Left(error)) =>
                  Task.now((state, Left(error)))
                case (state, Right(result)) if result.statusCode != bsp.StatusCode.Ok =>
                  Task.now(
                    (state, Left(JsonRpcResponse.internalError("Compilation not successful")))
                  )
                case (state, Right(_)) =>
                  val projects = mappings.map(_._2)
                  inferDebuggeeRunner(projects, state) match {
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
      Tasks.test(state, List(project), Nil, testFilter, handler, false, mode = RunMode.Normal)
    }

    val originId = params.originId
    ifInitialized(originId) { (state: State, logger: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.TestResult(originId, bsp.StatusCode.Error, None, None))))
        case Right(mappings) =>
          val args = params.arguments.getOrElse(Nil)
          compileProjects(mappings, state, args, originId, logger).flatMap {
            case (newState, compileResult) =>
              compileResult match {
                case Right(result) =>
                  val sequentialTestExecution = mappings.foldLeft(Task.now(newState)) {
                    case (taskState, (tid, p)) => taskState.flatMap(state => test(tid, p, state))
                  }

                  sequentialTestExecution.materialize.map(_.toEither).map {
                    case Right(newState) =>
                      (newState, Right(bsp.TestResult(originId, bsp.StatusCode.Ok, None, None)))
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

  def scalaMainClasses(
      params: bsp.ScalaMainClassesParams
  ): BspEndpointResponse[bsp.ScalaMainClassesResult] = {
    def findMainClasses(state: State, project: Project): List[bsp.ScalaMainClass] =
      for {
        className <- Tasks.findMainClasses(state, project)
      } yield bsp.ScalaMainClass(className, Nil, Nil)

    ifInitialized(params.originId) { (state: State, logger: BspServerLogger) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          logger.error(error)
          Task.now((state, Right(bsp.ScalaMainClassesResult(Nil))))

        case Right(projects) =>
          val items = for {
            (id, project) <- projects.toList
            mainClasses = findMainClasses(state, project)
          } yield bsp.ScalaMainClassesItem(id, mainClasses)

          val result = new bsp.ScalaMainClassesResult(items)
          Task.now((state, Right(result)))
      }
    }
  }

  def run(params: bsp.RunParams): BspEndpointResponse[bsp.RunResult] = {
    def getMainClass(project: Project, state: State): Either[Exception, bsp.ScalaMainClass] = {
      params.dataKind match {
        // TODO replace with RunParamsDataKind.ScalaMainClass once bsp updates
        case Some("scala-main-class") =>
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

      getMainClass(project, state) match {
        case Left(error) =>
          Task.now(sys.error(s"Failed to run main class in $project due to: ${error.getMessage}"))
        case Right(mainClass) =>
          project.platform match {
            case Platform.Jvm(javaEnv, _, _) =>
              val mainArgs = mainClass.arguments.toArray
              val env = JavaEnv(javaEnv.javaHome, javaEnv.javaOptions ++ mainClass.jvmOptions)
              Tasks.runJVM(
                state,
                project,
                env,
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
    ifInitialized(originId) { (state: State, logger: BspServerLogger) =>
      mapToProject(params.target, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          logger.error(error)
          Task.now((state, Right(bsp.RunResult(originId, bsp.StatusCode.Error))))
        case Right((tid, project)) =>
          val args = params.arguments.getOrElse(Nil)
          compileProjects(List((tid, project)), state, args, originId, logger).flatMap {
            case (newState, compileResult) =>
              compileResult match {
                case Right(result) =>
                  var isCancelled: Boolean = false
                  run(tid, project, newState)
                    .doOnCancel(Task { isCancelled = true; () })
                    .materialize
                    .map(_.toEither)
                    .map {
                      case Left(e) =>
                        val errorMsg =
                          JsonRpcResponse.internalError(s"Failed test execution: ${e.getMessage}")
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
      jars = jars
    )
  }

  def buildTargets(
      request: bsp.WorkspaceBuildTargetsRequest
  ): BspEndpointResponse[bsp.WorkspaceBuildTargetsResult] = {
    ifInitialized(None) { (state: State, logger: BspServerLogger) =>
      def reportBuildError(msg: String): Unit = {
        endpoints.Build.showMessage.notify(
          ShowMessageParams(MessageType.Error, None, None, msg)
        )(client)
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
              val tag = {
                if (p.name.endsWith("-test") && build.getProjectFor(s"${p.name}-test").isEmpty)
                  bsp.BuildTargetTag.Test
                else bsp.BuildTargetTag.Library
              }
              val deps = p.dependencies.iterator.flatMap(build.getProjectFor(_).toList)
              val extra = p.scalaInstance.map(i => encodeScalaBuildTarget(toScalaBuildTarget(p, i)))
              val capabilities = bsp.BuildTargetCapabilities(
                canCompile = true,
                canTest = true,
                canRun = true
              )
              val javaInstance = ScalaInstance.scalaInstanceForJavaProjects(logger)(ioScheduler)
              val isJavaOnly = p.scalaInstance == javaInstance
              val languageIds =
                if (isJavaOnly) BloopBspServices.JavaOnly
                else BloopBspServices.DefaultLanguages
              bsp.BuildTarget(
                id = id,
                displayName = Some(p.name),
                baseDirectory = Some(bsp.Uri(p.baseDirectory.toBspUri)),
                tags = List(tag),
                languageIds = languageIds,
                dependencies = deps.map(toBuildTargetId).toList,
                capabilities = capabilities,
                dataKind = Some(bsp.BuildTargetDataKind.Scala),
                data = extra
              )
            }
          )

          Task.now((state, Right(targets)))
        }
      }
    }
  }

  def sources(
      request: bsp.SourcesParams
  ): BspEndpointResponse[bsp.SourcesResult] = {
    def sources(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.SourcesResult] = {

      val response = bsp.SourcesResult(
        projects.iterator.map {
          case (target, project) =>
            val sources = project.sources.map {
              s =>
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
            bsp.SourcesItem(target, sources)
        }.toList
      )

      Task.now((state, Right(response)))
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
            val resources = project.resources.flatMap { s =>
              if (s.exists) {
                val resources = Files.walk(s.underlying).collect(Collectors.toList[Path]).asScala
                resources.map(r => bsp.Uri(r.toUri()))
              } else {
                Seq.empty
              }
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
                  .filter(a => a.classifier.toList.contains("sources"))
                  .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri))
                  .toList
              }
            }
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
            val dag = state.build.getDagFor(project)
            val fullClasspath = project.fullClasspath(dag, state.client)
            val classpath = fullClasspath.map(e => bsp.Uri(e.toBspUri)).toList
            val classesDir = state.client.getUniqueClassesDirFor(project).toBspUri
            bsp.ScalacOptionsItem(
              target = target,
              options = project.scalacOptions.toList,
              classpath = classpath,
              classDirectory = bsp.Uri(classesDir)
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
          // TODO(jvican): Add status code to scalac options result
          Task.now((state, Right(bsp.ScalacOptionsResult(Nil))))
        case Right(mappings) => scalacOptions(mappings, state)
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
