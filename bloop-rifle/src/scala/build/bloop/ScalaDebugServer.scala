package bloop.rifle;

import ch.epfl.scala.bsp4j.{DebugSessionAddress, DebugSessionParams}
import org.eclipse.lsp4j.jsonrpc.services.JsonRequest

import java.util.concurrent.CompletableFuture;

trait ScalaDebugServer {
  @JsonRequest("debugSession/start")
  def buildTargetDebugSession(params: DebugSessionParams): CompletableFuture[DebugSessionAddress]
}
