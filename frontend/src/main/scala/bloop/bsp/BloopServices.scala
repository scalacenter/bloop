package bloop.bsp

import bloop.cli.Commands
import bloop.engine.{Interpreter, State}
import bloop.io.AbsolutePath
import ch.epfl.`scala`.bsp.schema._
import monix.eval.{Task => MonixTask}
import ch.epfl.scala.bsp.endpoints
import org.langmeta.jsonrpc.{
  JsonRpcClient,
  Response => JsonRpcResponse,
  Services => JsonRpcServices
}
import org.langmeta.lsp.Window

class BloopServices(state: State, client: JsonRpcClient) {
  final val services = JsonRpcServices.empty
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .request(endpoints.Workspace.buildTargets)(buildTargets(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))

  private final val bspLogger = new bloop.logging.AbstractLogger() {
    override def name: String = "bsp-logger"
    override def verbose[T](op: => T): T = op
    override def debug(msg: String): Unit = Window.showMessage.info(msg)(client)
    override def error(msg: String): Unit = Window.showMessage.error(msg)(client)
    override def ansiCodesSupported(): Boolean = true
    override def warn(msg: String): Unit = Window.showMessage.warn(msg)(client)
    override def trace(t: Throwable): Unit = Window.showMessage.info(t.toString)(client)
    override def info(msg: String): Unit = Window.showMessage.info(msg)(client)
  }

  // Internal state, think how to make this more elegant.
  @volatile private final var currentState: State = null

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param initializeBuildParams The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(
      initializeBuildParams: InitializeBuildParams
  ): MonixTask[Either[JsonRpcResponse.Error, InitializeBuildResult]] = MonixTask {
    val uriToLoad = initializeBuildParams.rootUri
    val state0 = State.loadStateFor(AbsolutePath(uriToLoad), bspLogger)
    currentState = state0.copy(logger = bspLogger)
    System.err.println(initializeBuildParams.toString)
    Right(
      InitializeBuildResult(
        Some(
          BuildServerCapabilities(
            compileProvider = false,
            textDocumentBuildTargetsProvider = false,
            dependencySourcesProvider = false,
            buildTargetChangedProvider = false
          )
        )
      )
    )
  }

  def initialized(
      initializedBuildParams: InitializedBuildParams
  ): Unit = {
    System.err.println("Bloop has initialized with the client.")
  }

  def compile(params: CompileParams): MonixTask[Either[JsonRpcResponse.Error, CompileReport]] = {
    MonixTask {
      // TODO(jvican): Naive approach, we need to implement batching here.
      val projectsToCompile = params.targets.map { target =>
        ProjectUris.getProjectDagFromUri(target.uri, state) match {
          case Some(project) => project
          // TODO: Error handling here has to be rethought.
          case None => sys.error(s"The project for ${target.uri} is missing!")
        }
      }

      import bloop.engine.{Run, Exit, Action}
      import bloop.cli.ExitStatus
      val action = projectsToCompile.foldLeft(Exit(ExitStatus.Ok): Action) {
        case (action, project) => Run(Commands.Compile(project.name), action)
      }

      Interpreter.execute(action, currentState)
      Right(CompileReport(List()))
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
