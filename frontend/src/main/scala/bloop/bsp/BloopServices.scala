package bloop.bsp

import ch.epfl.`scala`.bsp.schema.{
  BuildServerCapabilities,
  CompileParams,
  CompileReport,
  InitializeBuildParams,
  InitializeBuildResult,
  InitializedBuildParams
}
import monix.eval.{Task => MonixTask}
import ch.epfl.scala.bsp.endpoints
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.jsonrpc.{
  JsonRpcClient,
  Response => JsonRpcResponse,
  Services => JsonRpcServices
}

class BloopServices(client: JsonRpcClient) extends LazyLogging {
  def get: JsonRpcServices = services
  private final val services = JsonRpcServices.empty
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param initializeBuildParams The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(
      initializeBuildParams: InitializeBuildParams
  ): MonixTask[Either[JsonRpcResponse.Error, InitializeBuildResult]] = MonixTask {
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
    logger.info("Bloop has initialized with the client.")
  }

  def compile(
      compileParams: CompileParams): MonixTask[Either[JsonRpcResponse.Error, CompileReport]] = {
    ???
  }
}
