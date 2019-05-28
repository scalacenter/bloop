package bloop.bsp

import java.net.Socket
import java.net.ServerSocket
import java.util.Locale

import bloop.cli.Commands
import bloop.data.ClientInfo
import bloop.engine.{ExecutionContext, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, DebugFilter}
import bloop.sockets.UnixDomainServerSocket
import bloop.sockets.Win32NamedPipeServerSocket

import monix.eval.Task
import monix.execution.Ack
import monix.execution.Scheduler
import monix.execution.Cancelable
import monix.execution.atomic.Atomic
import monix.execution.misc.NonFatal
import monix.reactive.OverflowStrategy
import monix.reactive.observers.Subscriber
import monix.reactive.{Observer, Observable}
import monix.reactive.observables.ObservableLike
import monix.execution.cancelables.CompositeCancelable

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import monix.execution.cancelables.AssignableCancelable
import java.nio.file.NoSuchFileException

object BspServer {
  private implicit val logContext: DebugFilter = DebugFilter.Bsp

  import java.net.InetSocketAddress
  private sealed trait ConnectionHandle { def serverSocket: ServerSocket }
  private case class WindowsLocal(pipeName: String, serverSocket: ServerSocket)
      extends ConnectionHandle
  private case class UnixLocal(path: AbsolutePath, serverSocket: ServerSocket)
      extends ConnectionHandle
  private case class Tcp(address: InetSocketAddress, serverSocket: ServerSocket)
      extends ConnectionHandle

  import Commands.ValidatedBsp
  private def initServer(cmd: ValidatedBsp, state: State): Task[ConnectionHandle] = {
    cmd match {
      case Commands.WindowsLocalBsp(pipeName, _) =>
        val server = new Win32NamedPipeServerSocket(pipeName)
        state.logger.debug(s"Waiting for a connection at pipe $pipeName...")
        Task(WindowsLocal(pipeName, server)).doOnCancel(Task(server.close()))
      case Commands.UnixLocalBsp(socketFile, _) =>
        val server = new UnixDomainServerSocket(socketFile.toString)
        state.logger.debug(s"Waiting for a connection at $socketFile...")
        Task(UnixLocal(socketFile, server)).doOnCancel(Task(server.close()))
      case Commands.TcpBsp(address, portNumber, _) =>
        val socketAddress = new InetSocketAddress(address, portNumber)
        val server = new java.net.ServerSocket(portNumber, 10, address)
        state.logger.debug(s"Waiting for a connection at $socketAddress...")
        Task(Tcp(socketAddress, server)).doOnCancel(Task(server.close()))
    }
  }

  private final val connectedBspClients =
    new ConcurrentHashMap[ClientInfo.BspClientInfo, AbsolutePath]()

  def run(
      cmd: ValidatedBsp,
      state: State,
      config: RelativePath,
      promiseWhenStarted: Option[Promise[Unit]],
      externalObserver: Option[Observer.Sync[State]],
      scheduler: Scheduler,
      ioScheduler: Scheduler
  ): Task[State] = {
    import state.logger
    def uri(handle: ConnectionHandle): String = {
      handle match {
        case w: WindowsLocal => s"local:${w.pipeName}"
        case u: UnixLocal => s"local://${u.path.syntax}"
        case t: Tcp => s"tcp://${t.address.getHostString}:${t.address.getPort}"
      }
    }

    def startServer(handle: ConnectionHandle): Task[State] = {
      val isCommunicationActive = Atomic(true)
      val connectionURI = uri(handle)

      // Do NOT change this log, it's used by clients to know when to start a connection
      logger.info(s"The server is listening for incoming connections at $connectionURI...")
      promiseWhenStarted.foreach(_.success(()))

      val socket = handle.serverSocket.accept()
      logger.info(s"Accepted incoming BSP client connection at $connectionURI")

      val in = socket.getInputStream
      val out = socket.getOutputStream

      // FORMAT: OFF
      val bspLogger = new BspClientLogger(logger)
      val client = new BloopLanguageClient(out, bspLogger)
      val messages = BaseProtocolMessage.fromInputStream(in, bspLogger)
      val stopBspConnection = AssignableCancelable.single()
      val provider = new BloopBspServices(state, client, config, stopBspConnection, externalObserver, isCommunicationActive, connectedBspClients, scheduler, ioScheduler)
      val server = new BloopLanguageServer(messages, client, provider.services, ioScheduler, bspLogger)
      // FORMAT: ON

      def error(msg: String): Unit = provider.stateAfterExecution.logger.error(msg)

      /* This implementation of starting a server relies on two observables:
       *
       *   1. An observable with a publish strategy that gets protocol messages
       *      and forwards them to the bloop server and services implementation.
       *   2. An observable that pumps input from the socket `InputStream`,
       *      parses it into BSP messages and forwards it to the previous
       *      observable.
       *
       * We use two observables instead of one because if the client crashes or
       * disconnects, we want to cancel all tasks triggered by the first
       * observable as soon as possible. If we were using only one observable,
       * we would not receive RST or FIN socket messages because the next
       * `read` call would not happen until the spawn server tasks are
       * finished. In our case, as soon as we have parsed a successful message,
       * we will call `read` and wait on a read result, EOF or a connection
       * reset/IO exception.
       */

      import monix.reactive.Observable
      import monix.reactive.MulticastStrategy
      val (bufferedObserver, endObservable) =
        Observable.multicast(MulticastStrategy.publish[BaseProtocolMessage])(ioScheduler)

      import scala.collection.mutable
      import monix.execution.cancelables.AssignableCancelable
      // We set the value of this cancelable when we start consuming task
      var completeSubscribers: Cancelable = Cancelable.empty
      val cancelables = new mutable.ListBuffer[Cancelable]()
      val cancelable = AssignableCancelable.multi { () =>
        val tasksToCancel = cancelables.synchronized { cancelables.toList }
        Cancelable.cancelAll(completeSubscribers :: tasksToCancel)
      }

      def onFinishOrCancel[T](cancelled: Boolean, result: Option[Throwable]) = Task {
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

          try {
            if (cancelled) error(s"BSP server cancelled, closing socket...")
            else result.foreach(t => error(s"BSP server stopped by ${t.getMessage}"))
            cancelable.cancel()
            server.cancelAllRequests()
          } finally {

            // Spawn deletion of orphan client directories every time we start a new connection
            ioScheduler.scheduleOnce(
              100,
              TimeUnit.MILLISECONDS,
              new Runnable {
                override def run(): Unit = {
                  ClientInfo.deleteOrphanClientBspDirectories(
                    () => askCurrentBspClients,
                    logger
                  )
                }
              }
            )

            // The code above should not throw, but move this code to a finalizer to be 100% sure
            closeCommunication(externalObserver, latestState, socket, handle.serverSocket)
            ()
          }
        }
      }

      import monix.reactive.Consumer
      val singleMessageConsumer = Consumer.foreachAsync[BaseProtocolMessage] { msg =>
        import scala.meta.jsonrpc.Response.Empty
        import scala.meta.jsonrpc.Response.Success
        val taskToRun = {
          server
            .handleMessage(msg)
            .flatMap(msg => Task.fromFuture(client.serverRespond(msg)).map(_ => ()))
            .onErrorRecover { case NonFatal(e) => bspLogger.error("Unhandled error", e); () }
        }

        val cancelable = taskToRun.runAsync(ioScheduler)
        cancelables.synchronized { cancelables.+=(cancelable) }
        Task
          .fromFuture(cancelable)
          .doOnFinish(_ => Task { cancelables.synchronized { cancelables.-=(cancelable) }; () })
      }

      val startedSubscription: Promise[Unit] = Promise[Unit]()

      /**
       * Make manual subscription to consumer so that we can control the
       * cancellation for both the source and the consumer. Otherwise, there is
       * no way to call the cancelable produced by the consumer.
       */
      val consumingWithBalancedForeach = Task.create[List[Unit]] { (scheduler, cb) =>
        val parallelConsumer = Consumer.loadBalance(4, singleMessageConsumer)
        val (out, consumerSubscription) = parallelConsumer.createSubscriber(cb, scheduler)
        val cancelOut = Cancelable(() => out.onComplete())
        completeSubscribers = CompositeCancelable(cancelOut)
        val sourceSubscription = endObservable.subscribe(out)
        startedSubscription.success(())
        CompositeCancelable(sourceSubscription, consumerSubscription)
      }

      val consumingTask = consumingWithBalancedForeach
        .doOnCancel(onFinishOrCancel(true, None))
        .doOnFinish(result => onFinishOrCancel(false, result))
        .flatMap(_ => server.awaitRunningTasks.map(_ => provider.stateAfterExecution))

      // Start consumer in the background and assign cancelable
      val consumerFuture = consumingTask.runAsync(ioScheduler)
      stopBspConnection.:=(Cancelable(() => consumerFuture.cancel()))

      /*
       * Defines a task that gets called whenever the socket `InputStream` is
       * closed. This can happen for several reasons:
       *
       *   1. Clients quickly sent an exit request and closed its socket input
       *      stream.
       *   2. Clients suddently crash/exit (especially when using Unix domain
       *      sockets and Windows named pipes as their implementation doesn't signal
       *      a forceful client close explicitly unlike TCP with `FIN` and `RST`,
       *      which means checking `isClosed` from the server side will always be
       *      false.)
       *
       * This task makes sure we stop any processing for this BSP client if we
       * haven't yet done that in the handling of exit within
       * `BloopBspServices`.
       */
      val cancelWhenStreamIsClosed: Task[Unit] = Task {
        if (!provider.exited.get) {
          consumerFuture.cancel()
        }
      }

      val startListeningToMessages = messages
        .liftByOperator(new PumpOperator(bufferedObserver, consumerFuture))
        .completedL
        .doOnFinish(_ => cancelWhenStreamIsClosed)
        .flatMap { _ =>
          Task.fromFuture(consumerFuture).map { latestState =>
            // Complete the states observable to signal exit on consumers
            externalObserver.foreach(_.onComplete())
            // Return the latest state
            latestState
          }
        }

      // Make sure we only start listening when the subscription has started,
      // there is a race condition and we might miss the initialization messages
      for {
        _ <- Task.fromFuture(startedSubscription.future).executeOn(ioScheduler)
        latestState <- startListeningToMessages.executeOn(ioScheduler)
      } yield latestState
    }

    initServer(cmd, state).materialize.flatMap {
      case scala.util.Success(handle: ConnectionHandle) =>
        startServer(handle).onErrorRecoverWith {
          case t => Task.now(state.withError(s"Exiting BSP server with ${t.getMessage}", t))
        }
      case scala.util.Failure(t: Throwable) =>
        promiseWhenStarted.foreach(p => if (!p.isCompleted) p.failure(t))
        Task.now(state.withError(s"BSP server failed to open a socket: '${t.getMessage}'", t))
    }
  }

  def closeCommunication(
      externalObserver: Option[Observer[State]],
      latestState: State,
      socket: Socket,
      serverSocket: ServerSocket
  ): Unit = {
    // Close any socket communication asap and swallow exceptions
    try {
      try socket.close()
      catch { case NonFatal(t) => () } finally {
        try serverSocket.close()
        catch { case NonFatal(t) => () }
      }
    } finally {
      // Guarantee that we always schedule the external classes directories deletion
      val deleteExternalDirsTasks = latestState.build.projects.map { project =>
        import bloop.io.Paths
        import java.io.IOException
        val externalClientClassesDir = latestState.client.getUniqueClassesDirFor(project)
        if (externalClientClassesDir == project.genericClassesDir) Task.now(())
        else Task.fork(Task.eval(Paths.delete(externalClientClassesDir))).materialize
      }

      val groups = deleteExternalDirsTasks.grouped(4).map(group => Task.gatherUnordered(group))
      Task
        .sequence(groups)
        .map(_.flatten)
        .map(_ => ())
        .runAsync(ExecutionContext.ioScheduler)

      ()
    }
  }

  final class PumpOperator[A](pumpTarget: Observer.Sync[A], runningFuture: Cancelable)
      extends ObservableLike.Operator[A, A] {
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
