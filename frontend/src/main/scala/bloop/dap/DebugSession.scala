package bloop.dap

import java.net.{InetSocketAddress, Socket}
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.logging.{Handler, Level, LogRecord, Logger => JLogger}
import bloop.logging.{DebugFilter, Logger}
import bloop.cli.{ExitStatus => DebuggeeExitStatus}
import com.microsoft.java.debug.core.adapter.{ProtocolServer => DapServer}
import com.microsoft.java.debug.core.protocol.Messages.{Request, Response}
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.{Events, JsonUtils}
import com.microsoft.java.debug.core.{Configuration, LoggerFactory}
import monix.eval.Task
import monix.execution.atomic.Atomic
import monix.execution.{Cancelable, Scheduler}
import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import com.microsoft.java.debug.core.adapter.IProviderContext

/**
 *  This debug adapter maintains the lifecycle of the debuggee in separation from JDI.
 *  The debuggee is started/closed together with the session.
 *
 *  This approach makes it necessary to handle the "launch" requests as the "attach" ones.
 *  The JDI address of the debuggee is obtained through the [[DebugSessionLogger]]
 */
final class DebugSession(
    socket: Socket,
    initialDebugState: DebugSession.State,
    context: IProviderContext,
    initialLogger: Logger,
    ioScheduler: Scheduler,
    loggerAdapter: DebugSession.LoggerAdapter
) extends DapServer(
      socket.getInputStream,
      socket.getOutputStream,
      context,
      DebugSession.loggerFactory(loggerAdapter)
    )
    with Cancelable {
  private type LaunchId = Int

  // A set of all processed launched requests by the client
  private val launchedRequests = mutable.Set.empty[LaunchId]

  private val isDisconnected = Atomic(false)
  private val endOfConnection = Promise[Unit]()
  private val debugAddress = Promise[InetSocketAddress]()
  private val sessionStatusPromise = Promise[DebugSession.ExitStatus]()
  private val attachedPromise = Promise[Unit]()
  private val debugState = new Synchronized(initialDebugState)

  /*
   * We reach an end of connection when all expected terminal events have been
   * sent from the DAP client to the DAP server. The event types will be
   * removed from the set as more requests are processed by the client.
   */
  private val expectedTerminalEvents = mutable.Set("terminated", "exited")

  /**
   * Redirects to [[startDebuggeeAndServer()]].
   * Prevents starting the server directly, without starting the debuggee
   */
  override def run(): Unit = {
    startDebuggeeAndServer()
  }

  /**
   * Schedules the start of the debugging session.
   *
   * For a session to start, two executions must happen independently in a
   * non-blocking way: the debuggee process is started in the background and
   * the DAP server starts listening to client requests in an IO thread.
   */
  def startDebuggeeAndServer(): Unit = {
    debugState.transform {
      case DebugSession.Ready(runner) =>
        def terminateGracefully(result: Option[Throwable]): Task[Unit] = {
          Task.fromFuture(endOfConnection.future).map { _ =>
            sessionStatusPromise.trySuccess(DebugSession.Terminated)
            socket.close()
          }
        }

        def cancelIfError(exitStatus: DebuggeeExitStatus): Unit = {
          if (!exitStatus.isOk) {
            cancelPromises(new Exception(exitStatus.name))
          }
        }

        Task(super.run()).runAsync(ioScheduler)

        val logger = new DebugSessionLogger(this, addr => debugAddress.success(addr), initialLogger)

        // all output events are sent before debuggee task gets finished
        val debuggee = runner
          .run(logger)
          .transform(cancelIfError, cancelPromises)
          .doOnFinish(terminateGracefully)
          .doOnCancel(terminateGracefully(None))
          .runAsync(ioScheduler)

        DebugSession.Started(debuggee)

      case otherState =>
        otherState // don't start if already started or cancelled
    }
  }

  override def dispatchRequest(request: Request): Unit = {
    val requestId = request.seq
    request.command match {
      case "launch" =>
        launchedRequests.add(requestId)
        Task
          .fromFuture(debugAddress.future)
          .timeout(FiniteDuration(15, TimeUnit.SECONDS))
          .runOnComplete {
            case Success(address) =>
              super.dispatchRequest(DebugSession.toAttachRequest(requestId, address))
            case Failure(exception) =>
              val cause = s"Could not start debuggee due to: ${exception.getMessage}"
              this.sendResponse(DebugSession.failed(request, cause))
              attachedPromise.tryFailure(new IllegalStateException(cause))
              ()
          }(ioScheduler)
        ()

      case "configurationDone" =>
        // Delay handling of this request until we attach to the debuggee.
        // Otherwise, a race condition may happen when we try to communicate
        // with the VM we are not connected to
        Task
          .fromFuture(attachedPromise.future)
          .runOnComplete {
            case Success(_) =>
              super.dispatchRequest(request)
            case Failure(exception) =>
              sendResponse(DebugSession.failed(request, exception.getMessage))
          }(ioScheduler)
        ()

      case "disconnect" =>
        try {
          if (DebugSession.shouldRestart(request)) {
            sessionStatusPromise.trySuccess(DebugSession.Restarted)
          }

          sendAcknowledgment(request)
        } finally {
          // Exited event should not be expected if debugger has already disconnected from the JVM
          expectedTerminalEvents.remove("exited")

          debugState.transform {
            case DebugSession.Started(debuggee) =>
              cancelPromises(new CancellationException("Client disconnected"))
              cancelDebuggee(debuggee)
              super.dispatchRequest(request)
              DebugSession.Cancelled
            case otherState => otherState
          }
        }
      case _ => super.dispatchRequest(request)
    }
  }

  override def sendResponse(response: Response): Unit = {
    val requestId = response.request_seq
    response.command match {
      case "attach" if launchedRequests(requestId) =>
        // Trick dap4j into thinking we're processing a launch instead of attach
        response.command = Command.LAUNCH.getName
        attachedPromise.success(())
        super.sendResponse(response)
      case "disconnect" =>
        // we are sending a response manually but the actual handler is also sending one so let's ignore it
        // because our disconnection must be successful as it is basically just cancelling the debuggee
        if (isDisconnected.compareAndSet(false, true)) {
          super.sendResponse(response)
        }
      case _ =>
        super.sendResponse(response)
    }
  }

  override def sendEvent(event: Events.DebugEvent): Unit = {
    try {
      super.sendEvent(event)

      if (event.`type` == "exited") loggerAdapter.onDebuggeeFinished()
    } finally {
      expectedTerminalEvents.remove(event.`type`)

      val isTerminated = expectedTerminalEvents.isEmpty
      if (isTerminated) {
        endOfConnection.trySuccess(()) // Might already be set when canceling
        () // Don't close socket, it terminates on its own
      }
    }
  }

  private def sendAcknowledgment(request: Request): Unit = {
    val ack = new Response(request.seq, request.command, true)
    sendResponse(ack)
  }

  /**
   * Completed, once this session exit status can be determined.
   * Those are: [[DebugSession.Terminated]] and [[DebugSession.Restarted]].
   * <p>Session gets the Terminated status when the communication stops without
   * the client ever requesting a restart.</p>
   * <p>Session becomes Restarted immediately when the restart request is received.
   * Note that the debuggee is still running and the communication with the client continues
   * (i.e. sending terminated and exited events).</p>
   */
  def exitStatus: Task[DebugSession.ExitStatus] = {
    Task.fromFuture(sessionStatusPromise.future)
  }

  /**
   * Cancels the background debuggee process, the DAP server and closes the socket.
   */
  def cancel(): Unit = {
    // Close connection after the timeout to prevent blocking if [[TerminalEvents]] are not sent
    def scheduleForcedEndOfConnection(): Unit = {
      val message = "Communication with DAP client is frozen, closing client forcefully..."
      Task
        .fromFuture(endOfConnection.future)
        .timeoutTo(FiniteDuration(5, TimeUnit.SECONDS), Task(initialLogger.warn(message)))
        .runOnComplete(_ => { endOfConnection.trySuccess(()); () })(ioScheduler)
      ()
    }

    debugState.transform {
      case DebugSession.Ready(_) =>
        socket.close()
        DebugSession.Cancelled

      case DebugSession.Started(debuggee) =>
        cancelPromises(new CancellationException("Debug session cancelled"))
        cancelDebuggee(debuggee)
        scheduleForcedEndOfConnection()
        DebugSession.Cancelled

      case DebugSession.Cancelled => DebugSession.Cancelled
    }
  }

  private def cancelDebuggee(debuggee: Cancelable): Unit = {
    loggerAdapter.onDebuggeeFinished()
    debuggee.cancel()
  }

  private def cancelPromises(cause: Throwable): Unit = {
    debugAddress.tryFailure(cause)
    attachedPromise.tryFailure(cause)
    ()
  }
}

object DebugSession {
  sealed trait ExitStatus
  final case object Restarted extends ExitStatus
  final case object Terminated extends ExitStatus

  sealed trait State
  final case class Ready(runner: DebuggeeRunner) extends State
  final case class Started(debuggee: Cancelable) extends State
  final case object Cancelled extends State

  def apply(
      socket: Socket,
      runner: DebuggeeRunner,
      logger: Logger,
      ioScheduler: Scheduler
  ): DebugSession = {
    val adapter = new LoggerAdapter(logger)
    val context = DebugExtensions.newContext(runner)
    val initialState = Ready(runner)
    new DebugSession(socket, initialState, context, logger, ioScheduler, adapter)
  }

  private[DebugSession] def toAttachRequest(seq: Int, address: InetSocketAddress): Request = {
    val arguments = new AttachArguments
    arguments.hostName = address.getHostName
    arguments.port = address.getPort

    val json = JsonUtils.toJsonTree(arguments, classOf[AttachArguments])
    new Request(seq, Command.ATTACH.getName, json.getAsJsonObject)
  }

  private[DebugSession] def failed(request: Request, message: String): Response = {
    new Response(request.seq, request.command, false, message)
  }

  private[DebugSession] def disconnectRequest(seq: Int): Request = {
    val arguments = new DisconnectArguments
    arguments.restart = false

    val json = JsonUtils.toJsonTree(arguments, classOf[DisconnectArguments])
    new Request(seq, Command.DISCONNECT.getName, json.getAsJsonObject)
  }

  private[DebugSession] def shouldRestart(disconnectRequest: Request): Boolean = {
    Try(JsonUtils.fromJson(disconnectRequest.arguments, classOf[DisconnectArguments]))
      .map(_.restart)
      .getOrElse(false)
  }

  private[DebugSession] def loggerFactory(handler: LoggerAdapter): LoggerFactory = { name =>
    val logger = JLogger.getLogger(name)
    logger.getHandlers.foreach(logger.removeHandler)
    logger.setUseParentHandlers(false)
    if (name == Configuration.LOGGER_NAME) logger.addHandler(handler)
    logger
  }

  private[DebugSession] final class LoggerAdapter(logger: Logger) extends Handler {
    private implicit val debugFilter: DebugFilter = DebugFilter.All

    /**
     * Debuggee tends to send a lot of SocketClosed exceptions when bloop is terminating the socket. This helps us filter those logs
     */
    @volatile private var debuggeeFinished = false

    override def publish(record: LogRecord): Unit = {
      val message = record.getMessage
      record.getLevel match {
        case Level.INFO | Level.CONFIG => logger.info(message)
        case Level.WARNING => logger.warn(message)
        case Level.SEVERE =>
          if (isExpectedDuringCancellation(message) || isIgnoredError(message)) {
            logger.debug(message)
          } else {
            logger.error(message)
          }
        case _ => logger.debug(message)
      }
    }

    private val socketClosed = "java.net.SocketException: Socket closed"
    private def isExpectedDuringCancellation(message: String): Boolean = {
      message.endsWith(socketClosed) && debuggeeFinished
    }

    private val recordingWhenVmDisconnected =
      "Exception on recording event: com.sun.jdi.VMDisconnectedException"
    private def isIgnoredError(message: String): Boolean = {
      message.startsWith(recordingWhenVmDisconnected)
    }

    def onDebuggeeFinished(): Unit = {
      debuggeeFinished = true
    }

    override def flush(): Unit = ()
    override def close(): Unit = ()
  }
}
