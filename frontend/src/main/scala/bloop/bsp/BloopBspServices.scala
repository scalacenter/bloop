package bloop.bsp

import java.util.concurrent.atomic.AtomicInteger

import bloop.cli.Commands
import bloop.engine.{Interpreter, State}
import bloop.io.AbsolutePath
import ch.epfl.`scala`.bsp.schema._
import monix.{eval => me}
import ch.epfl.scala.bsp.endpoints
import com.typesafe.scalalogging.Logger
import org.langmeta.jsonrpc.{
  JsonRpcClient,
  Response => JsonRpcResponse,
  Services => JsonRpcServices
}
import org.langmeta.lsp.Window

class BloopBspServices(callSiteState: State, client: JsonRpcClient, bspLogger: Logger) {
  final val services = JsonRpcServices.empty
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .request(endpoints.Workspace.buildTargets)(buildTargets(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))

  /**
   * Creates a logger that will forward all the messages to the underlying bsp client.
   * It does so via the replication of the `window/showMessage` LSP functionality.
   */
  private final val bspForwarderLogger = new bloop.logging.AbstractLogger() {
    override def name: String = s"bsp-logger-${BloopBspServices.counter.incrementAndGet()}"
    override def verbose[T](op: => T): T = callSiteState.logger.verbose(op)
    override def isVerbose: Boolean = callSiteState.logger.isVerbose
    override def ansiCodesSupported(): Boolean = true

    override def debug(msg: String): Unit = {
      callSiteState.logger.debug(msg)
    }

    override def error(msg: String): Unit = {
      callSiteState.logger.error(msg)
      Window.showMessage.error(msg)(client)
    }

    override def warn(msg: String): Unit = {
      callSiteState.logger.warn(msg)
      Window.showMessage.warn(msg)(client)
    }

    override def trace(t: Throwable): Unit = {
      callSiteState.logger.trace(t)
    }

    override def info(msg: String): Unit = {
      callSiteState.logger.info(msg)
      Window.showMessage.info(msg)(client)
    }
  }

  // Internal state, think how to make this more elegant.
  @volatile private final var currentState: State = null

  /**
   * Get the latest state that can be reused and cached by bloop so that the next client
   * can have access to it.
   */
  def latestState: State = {
    val state0 = if (currentState == null) callSiteState else currentState
    state0.copy(logger = callSiteState.logger)
  }

  private final val defaultOpts = callSiteState.commonOptions
  def reloadState(uri: String): State = {
    val state0 = State.loadActiveStateFor(AbsolutePath(uri), defaultOpts, bspForwarderLogger)
    state0.copy(logger = bspForwarderLogger, commonOptions = latestState.commonOptions)
  }

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param initializeBuildParams The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(
      initializeBuildParams: InitializeBuildParams
  ): me.Task[Either[JsonRpcResponse.Error, InitializeBuildResult]] = me.Task {
    callSiteState.logger.info("request received: build/initialize")
    bspLogger.info("request received: build/initialize")
    currentState = reloadState(initializeBuildParams.rootUri)
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

  def initialized(
      initializedBuildParams: InitializedBuildParams
  ): Unit = {
    bspLogger.info("BSP initialization handshake complete.")
  }

  def compile(params: CompileParams): me.Task[Either[JsonRpcResponse.Error, CompileReport]] = {
    me.Task {
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

      currentState = Interpreter.execute(action, currentState)
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
