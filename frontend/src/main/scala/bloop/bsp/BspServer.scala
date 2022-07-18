package bloop.bsp

import java.net.ServerSocket
import java.net.Socket
import java.nio.file.NoSuchFileException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

import bloop.cli.Commands
import bloop.data.ClientInfo
import bloop.engine.ExecutionContext
import bloop.engine.State
import bloop.io.AbsolutePath
import bloop.io.RelativePath
import bloop.io.ServerHandle
import bloop.logging.BspClientLogger
import bloop.logging.DebugFilter
import bloop.task.Task

import jsonrpc4s._
import monix.execution.Ack
import monix.execution.Cancelable
import monix.execution.CancelablePromise
import monix.execution.Scheduler
import monix.execution.atomic.Atomic
import monix.reactive.Observable
import monix.reactive.Observer
import monix.reactive.OverflowStrategy
import monix.reactive.observers.Subscriber
import monix.reactive.subjects.BehaviorSubject

object BspServer {
  private implicit val logContext: DebugFilter = DebugFilter.Bsp

  import Commands.ValidatedBsp
  private def initServer(handle: ServerHandle, state: State): Task[ServerSocket] = {
    state.logger.debug(s"Waiting for a connection at $handle...")
    val openSocket = handle.server
    Task(openSocket).doOnCancel(Task(openSocket.close()))
  }

  private final val connectedBspClients =
    new ConcurrentHashMap[ClientInfo.BspClientInfo, AbsolutePath]()

  def run(
      cmd: ValidatedBsp,
      state: State,
      config: RelativePath,
      promiseWhenStarted: Option[Promise[Unit]],
      externalObserver: Option[BehaviorSubject[State]],
      scheduler: Scheduler,
      ioScheduler: Scheduler
  ): Task[State] = {
    import state.logger

    def listenToConnection(handle: ServerHandle, serverSocket: ServerSocket): Task[State] = {
      val isCommunicationActive = Atomic(true)
      val connectionURI = handle.uri

      // Do NOT change this log, it's used by clients to know when to start a connection
      logger.info(s"The server is listening for incoming connections at $connectionURI...")
      promiseWhenStarted.foreach(_.success(()))

      val socket = serverSocket.accept()
      logger.info(s"Accepted incoming BSP client connection at $connectionURI")

      val in = socket.getInputStream
      val out = socket.getOutputStream

      val bspLogger = new BspClientLogger(logger)
      val stopBspConnection = CancelablePromise[Unit]()

      val client = BloopLanguageClient.fromOutputStream(out, bspLogger)
      val provider = new BloopBspServices(
        state,
        client,
        config,
        stopBspConnection,
        externalObserver,
        isCommunicationActive,
        connectedBspClients,
        scheduler,
        ioScheduler
      )
      // In this case BloopLanguageServer doesn't use input observable
      val server =
        new BloopLanguageServer(Observable.never, client, provider.services, ioScheduler, bspLogger)

      def error(msg: String): Unit = provider.stateAfterExecution.logger.error(msg)

      val inputExit = CancelablePromise[Unit]()
      val mesages =
        LowLevelMessage
          .fromInputStream(in, bspLogger)
          .guaranteeCase(_ => monix.eval.Task(inputExit.success(())))
          .asyncBoundary(OverflowStrategy.Unbounded) // allows to catch input stream close earlier
          .mapParallelOrdered(4) { bytes =>
            val msg = LowLevelMessage.toMsg(bytes)
            server
              .handleValidMessage(msg)
              .flatMap(msg => Task.fromFuture(client.serverRespond(msg)).map(_ => ()))
              .onErrorRecover { case NonFatal(e) => bspLogger.error("Unhandled error", e); () }
              .toMonixTask(ioScheduler)
          }
          .executeOn(ioScheduler, true)

      val process = Task.raceMany(
        Task.liftMonixTaskUncancellable(mesages.completedL),
        Task.fromFuture(inputExit.future),
        Task.fromFuture(stopBspConnection.future)
      )

      def stopListeting(cancelled: Boolean): Unit = {
        if (isCommunicationActive.getAndSet(false)) {
          val latestState = provider.stateAfterExecution
          val initializedClientInfo = provider.unregisterClient

          def askCurrentBspClients: Set[ClientInfo.BspClientInfo] = {
            import scala.collection.JavaConverters._
            val clients0 = connectedBspClients.keySet().asScala.toSet
            // Add client that will be removed from map always so that its
            // project directories are visited and orphan dirs pruned
            initializedClientInfo match {
              case Some(bspInfo) => clients0.+(bspInfo)
              case None => clients0
            }
          }
          if (cancelled) error(s"BSP server cancelled, closing socket...")
          else error(s"BSP server stopped")
          server.cancelAllRequests()
          ioScheduler.scheduleOnce(
            100,
            TimeUnit.MILLISECONDS,
            new Runnable {
              override def run(): Unit = {
                val ngout = state.commonOptions.ngout
                val ngerr = state.commonOptions.ngerr
                ClientInfo.deleteOrphanClientBspDirectories(askCurrentBspClients, ngout, ngerr)
              }
            }
          )
          closeCommunication(latestState, socket, serverSocket)
        }
      }

      process
        .doOnCancel(Task(stopListeting(cancelled = true)))
        .doOnFinish(_ => Task(stopListeting(cancelled = false)))
        .map(_ => provider.stateAfterExecution)
    }

    val handle = cmd match {
      case Commands.WindowsLocalBsp(pipeName, _) =>
        ServerHandle.WindowsLocal(pipeName)
      case Commands.UnixLocalBsp(socketFile, _) =>
        ServerHandle.UnixLocal(socketFile)
      case Commands.TcpBsp(address, portNumber, _) =>
        ServerHandle.Tcp(address, portNumber, backlog = 10)
    }

    initServer(handle, state).materialize.flatMap {
      case scala.util.Success(socket: ServerSocket) =>
        listenToConnection(handle, socket).onErrorRecover {
          case t =>
            state.withError(s"Exiting BSP server with ${t.getMessage}", t)
        }
      case scala.util.Failure(t: Throwable) =>
        promiseWhenStarted.foreach(p => if (!p.isCompleted) p.failure(t))
        Task.now(state.withError(s"BSP server failed to open a socket: '${t.getMessage}'", t))
    }
  }

  def closeCommunication(
      latestState: State,
      socket: Socket,
      serverSocket: ServerSocket
  ): Unit = {
    // Close any socket communication asap and swallow exceptions
    try {
      try socket.close()
      catch { case NonFatal(_) => () }
      finally {
        try serverSocket.close()
        catch { case NonFatal(_) => () }
      }
    } finally {
      // Guarantee that we always schedule the external classes directories deletion
      val deleteExternalDirsTasks = latestState.build.loadedProjects.map { loadedProject =>
        import bloop.io.Paths
        val project = loadedProject.project
        try {
          val externalClientClassesDir =
            latestState.client.getUniqueClassesDirFor(project, forceGeneration = false)
          val skipDirectoryManagement =
            externalClientClassesDir == project.genericClassesDir ||
              latestState.client.hasManagedClassesDirectories
          if (skipDirectoryManagement) Task.now(())
          else Task.eval(Paths.delete(externalClientClassesDir)).materialize
        } catch {
          case _: NoSuchFileException => Task.now(())
        }
      }

      val groups = deleteExternalDirsTasks.grouped(4).map(group => Task.gatherUnordered(group))
      Task
        .sequence(groups.toList)
        .map(_.flatten)
        .map(_ => ())
        .runAsync(ExecutionContext.ioScheduler)

      ()
    }
  }

  final class PumpOperator[A](pumpTarget: Observer.Sync[A], runningFuture: Cancelable)
      extends Observable.Operator[A, A] {
    def apply(out: Subscriber[A]): Subscriber[A] =
      new Subscriber[A] { self =>
        implicit val scheduler = out.scheduler
        private[this] val isActive = Atomic(true)

        def onNext(elem: A): Future[Ack] =
          out.onNext(elem).syncOnContinue {
            // Forward and ignore ack; safe because observer is sync
            pumpTarget.onNext(elem)
            ()
          }

        def onComplete(): Unit = {
          if (isActive.getAndSet(false))
            out.onComplete()
        }

        def onError(ex: Throwable): Unit = {
          if (isActive.getAndSet(false)) {
            // Complete instead of forwarding error so that completeL finishes
            out.onComplete()
            runningFuture.cancel()
          } else {
            scheduler.reportFailure(ex)
          }
        }
      }
  }
}
