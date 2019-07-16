package bloop.dap

import java.net.{ServerSocket, URI}

import bloop.io.ServerHandle
import bloop.logging.Logger
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}

import scala.collection.mutable
import scala.concurrent.Promise
import monix.execution.cancelables.MultiAssignmentCancelable
import monix.execution.atomic.AtomicBoolean

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
    val ongoingSession = MultiAssignmentCancelable()

    def listen(serverSocket: ServerSocket): Task[Unit] = {
      val listenAndServeClient = Task {
        listeningPromise.trySuccess(Some(handle.uri))
        val socket = serverSocket.accept()

        DebugSession.open(socket, runner.run, logger, ioScheduler).flatMap { session =>
          ongoingSession.:=(session)
          session.startDebuggeeAndServer()
          session.exitStatus
        }
      }.flatten

      listenAndServeClient.flatMap {
        case DebugSession.Restarted => listen(serverSocket)
        case DebugSession.Terminated => Task.eval(serverSocket.close())
      }
    }

    def closeServer(t: Option[Throwable]): Task[Unit] = {
      Task {
        if (closedServer.compareAndSet(false, true)) {
          listeningPromise.trySuccess(None)
          ongoingSession.cancel()
          try {
            handle.server.close()
          } catch {
            case e: Exception =>
              logger.error(
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
