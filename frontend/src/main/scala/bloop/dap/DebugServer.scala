package bloop.dap

import java.net.URI

import bloop.ConnectionHandle
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}
import scalaz.effect.IoExceptionOr.IoException

// TODO do we need this? task is not cancelled anywhere
final class DebugServer(val uri: URI, task: CancelableFuture[Unit])

object DebugServer {
  def run(debuggee: () => Debuggee)(ioScheduler: Scheduler): DebugServer = {
    val connection = ConnectionHandle.tcp(backlog = 10)

    val serverTask = Task {
      var open = true
      while (open && !Thread.currentThread().isInterrupted) {
        try {
          val socket = connection.serverSocket.accept()

          DebugSession
            .open(socket, debuggee())(ioScheduler)
            .map(session => session.run())
            .runAsync(ioScheduler)
        } catch {
          case _: IoException =>
            connection.close()
            open = false
        }
      }
    }

    val connectionTask = serverTask.runAsync(ioScheduler)
    new DebugServer(connection.uri, connectionTask)
  }
}
