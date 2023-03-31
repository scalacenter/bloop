package bloop.rifle

import ch.epfl.scala.{bsp4j => b}

import java.util.concurrent.CompletableFuture

trait ScalaDebugServerForwardStubs extends ScalaDebugServer {
  protected def forwardTo: ScalaDebugServer
  override def buildTargetDebugSession(
    params: b.DebugSessionParams
  ): CompletableFuture[b.DebugSessionAddress] =
    forwardTo.buildTargetDebugSession(params)
}
