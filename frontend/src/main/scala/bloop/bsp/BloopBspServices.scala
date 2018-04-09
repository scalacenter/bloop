package bloop.bsp

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

import bloop.cli.Commands
import bloop.engine.{Interpreter, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.BspLogger
import ch.epfl.`scala`.bsp.schema._
import monix.eval.Task
import ch.epfl.scala.bsp.endpoints
import com.typesafe.scalalogging.Logger
import org.langmeta.jsonrpc.{JsonRpcClient, Response => JsonRpcResponse, Services => JsonRpcServices}

final class BloopBspServices(
    callSiteState: State,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath,
    bspLogger: Logger
) {
  private val bspForwarderLogger = BspLogger(callSiteState, client)
  final val services = JsonRpcServices.empty
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .request(endpoints.Workspace.buildTargets)(buildTargets(_))
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
   * @param initializeBuildParams The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(
      initializeBuildParams: InitializeBuildParams
  ): Task[Either[JsonRpcResponse.Error, InitializeBuildResult]] = {
    val configDir = AbsolutePath(initializeBuildParams.rootUri).resolve(relativeConfigPath)
    reloadState(configDir).map { state =>
      callSiteState.logger.info("request received: build/initialize")
      bspLogger.info("request received: build/initialize")
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

  def initialized(
      initializedBuildParams: InitializedBuildParams
  ): Unit = {
    bspLogger.info("BSP initialization handshake complete.")
  }

  def compile(params: CompileParams): Task[Either[JsonRpcResponse.Error, CompileReport]] = {
    // TODO(jvican): Naive approach, we need to implement batching here.
    val projectsToCompile = params.targets.map { target =>
      ProjectUris.getProjectDagFromUri(target.uri, currentState) match {
        case Some(project) => (target, project)
        case None => sys.error(s"The project for ${target.uri} is missing!")
      }
    }

    import bloop.engine.{Run, Exit, Action}
    import bloop.cli.ExitStatus
    val action = projectsToCompile.foldLeft(Exit(ExitStatus.Ok): Action) {
      case (action, (_, project)) =>
        Run(Commands.Compile(project.name), action)
    }

    Interpreter.execute(action, Task.now(currentState)).map { state =>
      currentState = state
      val items = projectsToCompile.map(p => CompileReportItem(target = Some(p._1)))
      Right(CompileReport(items))
    }
  }

  def buildTargets(request: WorkspaceBuildTargetsRequest): WorkspaceBuildTargets = {
    val targets = currentState.build.projects.map { p =>
      val id = BuildTargetIdentifier(ProjectUris.toUri(p.baseDirectory, p.name).toString)
      // Returning scala and java for now.
      BuildTarget(Some(id), p.name, List("scala", "java"))
    }
    WorkspaceBuildTargets(targets)
  }
}

object BloopBspServices {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)
}
