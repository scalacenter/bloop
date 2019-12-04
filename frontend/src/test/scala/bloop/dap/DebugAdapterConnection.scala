package bloop.dap

import java.net.{InetSocketAddress, Socket, URI}

import bloop.dap.DebugTestEndpoints._
import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.eval.Task
import monix.execution.Scheduler
import scala.concurrent.Promise
import java.io.Closeable
import bloop.engine.ExecutionContext
import java.util.concurrent.TimeUnit
import monix.execution.Cancelable
import com.microsoft.java.debug.core.protocol.Responses.SetBreakpointsResponseBody
import com.microsoft.java.debug.core.protocol.Responses.ContinueResponseBody
import com.microsoft.java.debug.core.protocol.Responses.ScopesResponseBody
import com.microsoft.java.debug.core.protocol.Responses.StackTraceResponseBody
import com.microsoft.java.debug.core.protocol.Responses.VariablesResponseBody

/**
 * Manages a connection with a debug adapter.
 * It closes the connection after receiving a response to the 'disconnect' request
 */
private[dap] final class DebugAdapterConnection(
    val socket: Socket,
    adapter: DebugAdapterProxy
) extends Closeable {
  // Complete the promise in the background whenever the socket is closed
  val closedPromise = Promise[Unit]()
  var cancelCompleter: Cancelable = Cancelable.empty
  cancelCompleter = ExecutionContext.ioScheduler.scheduleAtFixedRate(
    100,
    200,
    TimeUnit.MILLISECONDS,
    new Runnable() {
      def run(): Unit = {
        if (socket.isClosed()) {
          closedPromise.trySuccess(())
          cancelCompleter.cancel()
        }
      }
    }
  )

  def initialize(): Task[Capabilities] = {
    val arguments = new InitializeArguments()
    // These are the defaults specified in the DAP specification
    arguments.linesStartAt1 = true
    arguments.columnsStartAt1 = true
    adapter.request(Initialize, arguments)
  }

  def initialized: Task[Events.InitializedEvent] = {
    adapter.next(Initialized)
  }

  def setBreakpoints(
      arguments: SetBreakpointArguments
  ): Task[SetBreakpointsResponseBody] = {
    adapter.request(SetBreakpoints, arguments)
  }

  def stackTrace(threadId: Long): Task[StackTraceResponseBody] = {
    val arguments = new StackTraceArguments()
    arguments.threadId = threadId
    adapter.request(StackTrace, arguments)
  }

  def scopes(frameId: Int): Task[ScopesResponseBody] = {
    val arguments = new ScopesArguments
    arguments.frameId = frameId
    adapter.request(Scopes, arguments)
  }

  def variables(variablesReference: Int): Task[VariablesResponseBody] = {
    val arguments = new VariablesArguments
    arguments.variablesReference = variablesReference
    adapter.request(Variables, arguments)
  }

  def stopped: Task[Events.StoppedEvent] = {
    adapter.next(StoppedEvent)
  }

  def continue(threadId: Long): Task[ContinueResponseBody] = {
    val arguments = new ContinueArguments()
    arguments.threadId = threadId
    adapter.request(Continue, arguments)
  }

  def configurationDone(): Task[Unit] = {
    adapter.request(ConfigurationDone, ())
  }

  def launch(): Task[Unit] = {
    val arguments = new LaunchArguments
    arguments.noDebug = true
    adapter.request(Launch, arguments)
  }

  def close(): Unit = {
    try socket.close()
    finally {
      closedPromise.trySuccess(())
      ()
    }
  }

  def disconnect(restart: Boolean): Task[Unit] = {
    val arguments = new DisconnectArguments
    arguments.restart = restart
    for {
      response <- adapter.request(Disconnect, arguments)
      _ <- Task(close())
    } yield response
  }

  def exited: Task[Events.ExitedEvent] = {
    adapter.next(Exited)
  }

  def terminated: Task[Events.TerminatedEvent] = {
    adapter.next(Terminated)
  }

  def takeCurrentOutput: Task[String] = {
    Task(adapter.takeCurrentOutput)
  }

  def blockForAllOutput: Task[String] = {
    adapter.blockForAllOutput
  }
}

object DebugAdapterConnection {
  def connectTo(uri: URI)(scheduler: Scheduler): DebugAdapterConnection = {
    val socket = new Socket()
    socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), 500)

    val proxy = DebugAdapterProxy(socket)
    proxy.startBackgroundListening(scheduler)
    new DebugAdapterConnection(socket, proxy)
  }
}
