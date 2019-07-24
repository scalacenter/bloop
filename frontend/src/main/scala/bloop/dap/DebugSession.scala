package bloop.dap

import java.net.{InetSocketAddress, Socket}
import java.util.concurrent.TimeUnit
import java.util.logging.{Handler, Level, LogRecord, Logger => JLogger}

import bloop.dap.DebugSession._
import bloop.logging.{DebugFilter, Logger}
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
import scala.util.Try

/**
 *  This debug adapter maintains the lifecycle of the debuggee in separation from JDI.
 *  The debuggee is started/closed together with the session.
 *
 *  This approach makes it necessary to handle the "launch" requests as the "attach" ones.
 *  The JDI address of the debuggee is obtained through the [[DebugSessionLogger]]
 */
final class DebugSession(
    socket: Socket,
    initialState: State,
    initialLogger: Logger,
    ioScheduler: Scheduler,
    loggerAdapter: LoggerAdapter
) extends DapServer(
      socket.getInputStream,
      socket.getOutputStream,
      DebugExtensions.newContext,
      DebugSession.loggerFactory(loggerAdapter)
    )
    with Cancelable {
  private type LaunchId = Int

  // A set of all processed launched requests by the client
  private val launchedRequests = mutable.Set.empty[LaunchId]

  private val communicationDone = Promise[Unit]()
  private val debugAddress = Promise[InetSocketAddress]()
  private val exitStatusPromise = Promise[DebugSession.ExitStatus]()

  private val state = new Synchronized(initialState)

  // contains all [[DebugSession.TerminalEvents]] already sent.
  // Communication is done only when all of them were sent.
  private val terminalEventsSent = mutable.Set.empty[String]

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
    state.transform {
      case Idle(runner) =>
        Task(super.run())
          .runOnComplete(_ => {
            exitStatusPromise.trySuccess(DebugSession.Terminated); ()
            Task
              .fromFuture(communicationDone.future)
              .timeoutTo(FiniteDuration(5, TimeUnit.SECONDS), Task(()))
              .runOnComplete(_ => socket.close())(ioScheduler)
            ()
          })(ioScheduler)

        val logger =
          new DebugSessionLogger(this, address => debugAddress.success(address), initialLogger)
        val debuggee = runner(logger).runAsync(ioScheduler)
        Started(debuggee)

      case otherState =>
        otherState // don't start if already started or cancelled
    }
  }

  override def dispatchRequest(request: Request): Unit = {
    val requestId = request.seq
    request.command match {
      case "launch" =>
        launchedRequests.add(requestId)
        val _ = Task
          .fromFuture(debugAddress.future)
          .map(DebugSession.toAttachRequest(requestId, _))
          .foreachL(super.dispatchRequest)
          .timeoutTo(
            FiniteDuration(5, TimeUnit.SECONDS),
            Task {
              val response = DebugSession.failed(request, "Could not start debuggee")
              this.sendResponse(response)
            }
          )
          .runAsync(ioScheduler)

      case "disconnect" =>
        if (DebugSession.shouldRestart(request)) {
          exitStatusPromise.trySuccess(DebugSession.Restarted)
        }

        state.transform {
          case Started(debuggee) =>
            cancelDebuggee(debuggee)
            Cancelled
          case otherState =>
            otherState
        }
        super.dispatchRequest(request)

      case _ => super.dispatchRequest(request)
    }
  }

  override def sendResponse(response: Response): Unit = {
    val requestId = response.request_seq
    response.command match {
      case "attach" if launchedRequests(requestId) =>
        // Trick dap4j into thinking we're processing a launch instead of attach
        response.command = Command.LAUNCH.getName
        super.sendResponse(response)
      case _ =>
        super.sendResponse(response)
    }
  }

  override def sendEvent(event: Events.DebugEvent): Unit = {
    try {
      super.sendEvent(event)
    } finally {
      if (DebugSession.TerminalEvents.contains(event.`type`)) {
        terminalEventsSent.add(event.`type`)
        if (terminalEventsSent == DebugSession.TerminalEvents) {
          communicationDone.success(())
          // cannot close the socket here - communication should now terminate on its own
        }
      }
    }
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
    Task.fromFuture(exitStatusPromise.future)
  }

  /**
   * Cancels the background debuggee process, the DAP server and closes the socket.
   */
  def cancel(): Unit = {
    state.transform {
      case Idle(_) =>
        socket.close()
        Cancelled

      case Started(debuggee) =>
        cancelDebuggee(debuggee)

        Task
          .fromFuture(communicationDone.future)
          .doOnFinish(_ => Task(socket.close()))
          .timeoutTo(
            FiniteDuration(5, TimeUnit.SECONDS),
            Task {
              initialLogger.warn(
                "Could not close the debug adapter gracefully. It will be terminated forcefully."
              )
              socket.close()
            }
          )
          .runAsync(ioScheduler)

        Cancelled

      case Cancelled =>
        Cancelled
    }
  }

  private def cancelDebuggee(debuggee: Cancelable): Unit = {
    loggerAdapter.onDebuggeeCancel()
    debuggee.cancel()
  }
}

object DebugSession {
  private[DebugSession] val TerminalEvents = Set("exited", "terminated")

  sealed trait ExitStatus
  final case object Restarted extends ExitStatus
  final case object Terminated extends ExitStatus

  sealed trait State
  final case class Idle(runner: DebugSessionLogger => Task[Unit]) extends State
  final case class Started(debuggee: Cancelable) extends State
  final case object Cancelled extends State

  def apply(
      socket: Socket,
      startDebuggee: DebugSessionLogger => Task[Unit],
      initialLogger: Logger,
      ioScheduler: Scheduler
  ): DebugSession = {
    val loggerAdapter = new LoggerAdapter(initialLogger)
    val initialState = Idle(startDebuggee)
    new DebugSession(socket, initialState, initialLogger, ioScheduler, loggerAdapter)
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

    if (name == Configuration.LOGGER_NAME) {
      logger.addHandler(handler)
    }
    logger
  }

  private[DebugSession] final class LoggerAdapter(logger: Logger) extends Handler {
    private implicit val debugFilter: DebugFilter = DebugFilter.All

    /**
     * Debuggee tends to send a lot of SocketClosed exceptions when bloop is terminating the socket. This helps us filter those logs
     */
    private val cancelled = Atomic(false)

    override def publish(record: LogRecord): Unit = {
      val message = record.getMessage
      record.getLevel match {
        case Level.WARNING =>
          logger.warn(message)
        case Level.SEVERE =>
          if (isExpectedDuringCancellation(message) || isIgnoredError(message)) {
            logger.debug(message)
          } else {
            logger.error(message)
          }
        case Level.INFO | Level.CONFIG =>
          logger.info(message)
        case _ =>
          logger.debug(message)
      }
    }

    val socketClosed = "java.net.SocketException: Socket closed"
    private def isExpectedDuringCancellation(message: String): Boolean = {
      cancelled.get && message.endsWith(socketClosed)
    }

    val recordingWhenVmDisconnected =
      "Exception on recording event: com.sun.jdi.VMDisconnectedException"
    private def isIgnoredError(message: String): Boolean = {
      message.startsWith(recordingWhenVmDisconnected)
    }

    def onDebuggeeCancel(): Unit = {
      cancelled.set(true)
    }

    override def flush(): Unit = {}
    override def close(): Unit = {}
  }
}
