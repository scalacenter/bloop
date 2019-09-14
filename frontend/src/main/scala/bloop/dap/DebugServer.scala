package bloop.dap

import java.net.{ServerSocket, URI}

import bloop.io.ServerHandle
import bloop.logging.Logger
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicBoolean
import monix.execution.cancelables.CompositeCancelable

import scala.concurrent.Promise

final class StartedDebugServer(
    val address: Task[Option[URI]],
    val listen: Task[Unit]
)

object DebugServer {
  def start(
      runner: DebuggeeRunner,
      logger: Logger,
      ioScheduler: Scheduler
  ): StartedDebugServer = {
    /*
     * Set backlog to 1 to recommend the OS to process one connection at a time,
     * which can happen when a restart is request and the client immediately
     * connects without waiting for the other session to finish.
     */
    val handle = ServerHandle.Tcp(backlog = 1)

    val closedServer = AtomicBoolean(false)
    val listeningPromise = Promise[Option[URI]]()
    val ongoingSessions = CompositeCancelable()

    def listen(serverSocket: ServerSocket): Task[Unit] = {
      val session = Task {
        listeningPromise.trySuccess(Some(handle.uri))
        val socket = serverSocket.accept()

        val session = DebugSession(socket, runner.run, logger, ioScheduler)
        ongoingSessions += session

        session.startDebuggeeAndServer()
        session.exitStatus
      }.flatten

      session
        .restartUntil(_ == DebugSession.Terminated)
        .map(_ => ())
    }

    def closeServer(t: Option[Throwable]): Task[Unit] = {
      Task {
        if (closedServer.compareAndSet(false, true)) {
          listeningPromise.trySuccess(None)
          ongoingSessions.cancel()
          try {
            handle.server.close()
          } catch {
            case e: Exception =>
              logger.warn(
                s"Could not close debug server listening on [${handle.uri} due to: ${e.getMessage}]"
              )
          }
        }
      }
    }

    val uri = Task.fromFuture(listeningPromise.future)
    val startAndListen = listen(handle.server)
      .doOnFinish(closeServer)
      .doOnCancel(closeServer(None))

    new StartedDebugServer(uri, startAndListen)
  }
}
