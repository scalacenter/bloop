package bloop.bsp

import java.util.concurrent.atomic.AtomicInteger

import bloop.Project
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Action, Dag, Exit, Interpreter, Run, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.BspLogger
import ch.epfl.`scala`.bsp.schema._
import monix.eval.Task
import ch.epfl.scala.bsp.endpoints
import org.langmeta.jsonrpc.{
  JsonRpcClient,
  Response => JsonRpcResponse,
  Services => JsonRpcServices
}

final class BloopBspServices(
    callSiteState: State,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath
) {
  private type ProtocolError = JsonRpcResponse.Error
  private type BspResponse[T] = Task[Either[ProtocolError, T]]
  private val bspForwarderLogger = BspLogger(callSiteState, client)
  final val services = JsonRpcServices.empty
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .requestAsync(endpoints.Workspace.buildTargets)(buildTargets(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))

  // Internal state, think how to make this more elegant.
  @volatile private var currentState: State = null

  /**
   * Get the latest state that can be reused and cached by bloop so that the next client
   * can have access to it.
   */
  def latestState: State = {
    val state0 = if (currentState == null) callSiteState else currentState
    state0.copy(logger = callSiteState.logger)
  }

  private val pool = callSiteState.pool
  private val defaultOpts = callSiteState.commonOptions
  def reloadState(config: AbsolutePath): Task[State] = {
    bspForwarderLogger.debug(s"Reloading bsp state for ${config.syntax}")
    State.loadActiveStateFor(config, pool, defaultOpts, bspForwarderLogger).map { state0 =>
      state0.copy(logger = bspForwarderLogger, commonOptions = latestState.commonOptions)
    }
  }

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param params The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(params: InitializeBuildParams): BspResponse[InitializeBuildResult] = {
    val configDir = AbsolutePath(params.rootUri).resolve(relativeConfigPath)
    reloadState(configDir).map { state =>
      callSiteState.logger.info("request received: build/initialize")
      currentState = state
      Right(
        InitializeBuildResult(
          Some(
            BuildServerCapabilities(
              compileProvider = true,
              textDocumentBuildTargetsProvider = true,
              dependencySourcesProvider = false,
              buildTargetChangedProvider = true
            )
          )
        )
      )
    }
  }

  private var isInitialized: Boolean = false
  def initialized(
      initializedBuildParams: InitializedBuildParams
  ): Unit = {
    isInitialized = true
    callSiteState.logger.info("BSP initialization handshake complete.")
  }

  def ifInitialized[T](t: => BspResponse[T]): BspResponse[T] = {
    if (isInitialized) t
    else Task.now(Left(JsonRpcResponse.invalidRequest("The session has not been initialized.")))
  }

  type ProjectMapping = (BuildTargetIdentifier, Project)
  private def mapToProjects(
      targets: Seq[BuildTargetIdentifier]): Either[ProtocolError, Seq[ProjectMapping]] = {
    def getProject(target: BuildTargetIdentifier): Either[ProtocolError, ProjectMapping] = {
      val uri = target.uri
      ProjectUris.getProjectDagFromUri(uri, currentState) match {
        case scala.util.Failure(e) =>
          Left(
            JsonRpcResponse.parseError(
              s"URI '${uri}' has invalid format. Example: ${ProjectUris.Example}"))
        case scala.util.Success(o) =>
          o match {
            case Some(project) => Right((target, project))
            case None => Left(JsonRpcResponse.invalidRequest(s"No project associated with $uri"))
          }
      }
    }

    targets.headOption match {
      case Some(head) =>
        val init = getProject(head).map(m => m :: Nil)
        targets.tail.foldLeft(init) {
          case (acc, t) => acc.flatMap(ms => getProject(t).map(m => m :: ms))
        }
      case None =>
        Left(
          JsonRpcResponse.invalidParams(
            "Empty build targets. Expected at least one build target identifier."))
    }
  }

  def compile(params: CompileParams): BspResponse[CompileReport] = {
    def compile(projects: Seq[ProjectMapping]): BspResponse[CompileReport] = {
      // TODO(jvican): Naive approach, we need to implement batching here.
      val action = projects.foldLeft(Exit(ExitStatus.Ok): Action) {
        case (action, (_, project)) => Run(Commands.Compile(project.name), action)
      }

      Interpreter.execute(action, Task.now(currentState)).map { state =>
        currentState = state
        val items = projects.map(p => CompileReportItem(target = Some(p._1)))
        Right(CompileReport(items))
      }
    }

    ifInitialized {
      mapToProjects(params.targets) match {
        case l @ Left(error) => Task.now(Left(error))
        case Right(mappings) => compile(mappings)
      }
    }
  }

  def buildTargets(request: WorkspaceBuildTargetsRequest): BspResponse[WorkspaceBuildTargets] = {
    ifInitialized {
      val targets = WorkspaceBuildTargets(
        currentState.build.projects.map { p =>
          val id = BuildTargetIdentifier(ProjectUris.toUri(p.baseDirectory, p.name).toString)
          BuildTarget(Some(id), p.name, List("scala", "java"))
        }
      )

      Task.now(Right(targets))
    }
  }
}

object BloopBspServices {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)
}
