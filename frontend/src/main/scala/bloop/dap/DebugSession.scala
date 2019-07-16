package bloop.dap

import java.net.{InetSocketAddress, Socket}
import java.util.concurrent.TimeUnit

import monix.execution.atomic.Atomic
import com.microsoft.java.debug.core.adapter.{ProtocolServer => DapServer}
import com.microsoft.java.debug.core.protocol.Messages.{Request, Response}
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.{Events, JsonUtils}
import monix.eval.Task
import monix.execution.{Cancelable, CancelableFuture, Scheduler}

import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import bloop.logging.Logger

/**
 *  This debug adapter maintains the lifecycle of the debuggee in separation from JDI.
 *  The debuggee is started/closed together with the session.
 *
 *  This approach makes it necessary to handle the "launch" requests as the "attach" ones.
 *  The JDI address of the debuggee is obtained through the [[DebugSessionLogger]]
 */
final class DebugSession(
    socket: Socket,
    startDebuggee: DebugSessionLogger => Task[Unit],
    initialLogger: Logger,
    ioScheduler: Scheduler
) extends DapServer(socket.getInputStream, socket.getOutputStream, DebugExtensions.newContext)
    with Cancelable {
  private type LaunchId = Int

  // A set of all processed launched requests by the client
  private val launchedRequests = mutable.Set.empty[LaunchId]

  private val debuggeeExited = Promise[Unit]()
  private val debugAddress = Promise[InetSocketAddress]()
  private val exitStatusPromise = Promise[DebugSession.ExitStatus]()

  private val isStarted = Atomic(false)
  private val isCancelled = Atomic(false)

  // Access to this cancelable is always protected behind `isCancelled` to avoid race conditions
  private val runningDebuggee = Atomic(CancelableFuture.unit)

  /**
   * Schedules the start of the debugging session.
   *
   * For a session to start, two executions must happen independently in a
   * non-blocking way: the debuggee process is started in the background and
   * the DAP server starts listening to client requests in an IO thread.
   */
  def startDebuggeeAndServer(): Unit = {
    ioScheduler.executeAsync(() => {
      if (isStarted.compareAndSet(false, true)) {
        val logger =
          new DebugSessionLogger(this, address => debugAddress.success(address), initialLogger)
        val debuggeeHandle = startDebuggee(logger).runAsync(ioScheduler)
        isCancelled.getAndTransform { isCancelled =>
          if (isCancelled) debuggeeHandle.cancel()
          else runningDebuggee.set(debuggeeHandle)
          isCancelled
        }

        try super.run()
        finally {
          exitStatusPromise.trySuccess(DebugSession.Terminated); ()
        }
      }
    })
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
          .runAsync(ioScheduler)

      case "disconnect" =>
        if (DebugSession.shouldRestart(request))
          exitStatusPromise.trySuccess(DebugSession.Restarted)
        runningDebuggee.get.cancel()
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
      case "disconnect" if requestId == DebugSession.InternalRequestId =>
        // Request sent by the session itself, don't return to client
        socket.close()
      case _ => super.sendResponse(response)
    }
  }

  override def sendEvent(event: Events.DebugEvent): Unit = {
    if (event.`type` == "exited") {
      debuggeeExited.success(())
    }

    super.sendEvent(event)
  }

  def exitStatus: Task[DebugSession.ExitStatus] = {
    Task.fromFuture(exitStatusPromise.future)
  }

  /**
   * Cancels the background debuggee process, the DAP server and closes the socket.
   */
  def cancel(): Unit = {
    if (isCancelled.compareAndSet(false, true)) {
      runningDebuggee.get.cancel()
      Task
        .fromFuture(debuggeeExited.future)
        .map(_ => DebugSession.disconnectRequest(DebugSession.InternalRequestId))
        .foreachL(dispatchRequest)
        .doOnFinish(_ => Task(socket.close()))
        .timeoutTo(FiniteDuration(5, TimeUnit.SECONDS), Task(socket.close()))
        .runAsync(ioScheduler)
      ()
    }
  }

}

object DebugSession {
  private[DebugSession] val InternalRequestId = Int.MinValue

  sealed trait ExitStatus
  final case object Restarted extends ExitStatus
  final case object Terminated extends ExitStatus

  def open(
      socket: Socket,
      startDebuggee: DebugSessionLogger => Task[Unit],
      logger: Logger,
      ioScheduler: Scheduler
  ): Task[DebugSession] = {
    for {
      _ <- Task.fromTry(JavaDebugInterface.isAvailable)
    } yield new DebugSession(socket, startDebuggee, logger, ioScheduler)
  }

  private[DebugSession] def toAttachRequest(seq: Int, address: InetSocketAddress): Request = {
    val arguments = new AttachArguments
    arguments.hostName = address.getHostName
    arguments.port = address.getPort

    val json = JsonUtils.toJsonTree(arguments, classOf[AttachArguments])
    new Request(seq, Command.ATTACH.getName, json.getAsJsonObject)
  }

  private[DebugSession] def disconnectRequest(seq: Int): Request = {
    val arguments = new DisconnectArguments
    arguments.restart = false

    val json = JsonUtils.toJsonTree(arguments, classOf[DisconnectArguments])
    new Request(seq, Command.DISCONNECT.getName, json.getAsJsonObject)
  }

  def shouldRestart(disconnectRequest: Request): Boolean = {
    Try(JsonUtils.fromJson(disconnectRequest.arguments, classOf[DisconnectArguments]))
      .map(_.restart)
      .getOrElse(false)
  }
}
