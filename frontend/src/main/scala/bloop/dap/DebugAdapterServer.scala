package bloop.dap

import java.net.URI

import bloop.ConnectionHandle
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.Success

final object DebugAdapterServer {
  def createAdapter()(implicit ioScheduler: Scheduler): URI = {
    val server = createServer()

    ioScheduler.executeAsync(() => server.serverSocket.accept())

    server.uri
  }

  private def createServer(): ConnectionHandle = {
    ConnectionHandle.tcp(backlog = 10) // TODO tune backlog?
  }
}
