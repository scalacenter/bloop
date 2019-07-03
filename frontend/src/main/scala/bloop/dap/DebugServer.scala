package bloop.dap

import java.net.{ServerSocket, URI}

import bloop.io.ServerHandle
import monix.eval.Task
import monix.execution.{Cancelable, CancelableFuture, Scheduler}

import scala.collection.mutable
import scala.concurrent.Promise

/**
 * @param address - an URI of this server, available one the server starts listening for clients.
 *                None, if server failes to start listening
 */
final class DebugServer(address: Task[Option[URI]], task: Task[Unit]) extends Cancelable {
  private var running: CancelableFuture[Unit] = _

  def run(scheduler: Scheduler): Task[Option[URI]] = synchronized {
    if (running == null) {
      running = task.runAsync(scheduler)
    }
    address
  }

  override def cancel(): Unit = synchronized {
    if (running != null) running.cancel()
  }
}

object DebugServer {
  def create(
      adapter: DebugAdapter,
      ioScheduler: Scheduler
  ): DebugServer = {
    // backlog == 1 means that only one connection should be waiting to be handled at a time.
    // "Should", since this parameter is entirely
    // This will happen when the restart is requested:
    // 1. Current session will be canceled
    // 2. The client will attempt to connect without waiting for the other session to finish
    //
    // note that the server will start to listen for another connection as soon as we can determine whether the
    // debug session was terminated or restarted. See [[DebugSession.exitStatus()]]
    val handle = ServerHandle.Tcp(backlog = 1)

    val listeningPromise = Promise[Option[URI]]()
    val servedRequests = mutable.Set[DebugSession]()
    def listen(serverSocket: ServerSocket): Task[Unit] = {
      val listenAndServeClient = Task {
        listeningPromise.trySuccess(Some(handle.uri))
        val socket = serverSocket.accept()
        DebugSession.open(socket, adapter.run, ioScheduler).flatMap { session =>
          servedRequests.add(session)

          session.run()

          val awaitExit = session.exitStatus()
          awaitExit
            .doOnFinish(_ => Task.eval(servedRequests.remove(session)))
            .doOnCancel(Task {
              servedRequests.remove(session)
              session.cancel()
            })
        }
      }.flatten

      listenAndServeClient.flatMap {
        case DebugSession.Restarted => listen(serverSocket)
        case DebugSession.Terminated => Task.eval(serverSocket.close())
      }
    }

    def closeServer(t: Option[Throwable]): Task[Unit] = {
      Task {
        listeningPromise.trySuccess(None)
        Cancelable.cancelAll(servedRequests)
        // TODO: Think how to handle exceptions thrown here
        handle.server.close()
      }
    }

    val uri = Task.fromFuture(listeningPromise.future)
    val startAndListen = listen(handle.server)
      .doOnFinish(closeServer)
      .doOnCancel(closeServer(None))

    new DebugServer(uri, startAndListen)
  }
}
