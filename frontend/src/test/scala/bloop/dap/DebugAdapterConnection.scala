package bloop.dap

import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

import scala.concurrent.Promise

import bloop.dap.DebugTestEndpoints._
import bloop.engine.ExecutionContext
import bloop.task.Task

import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.Responses.ContinueResponseBody
import com.microsoft.java.debug.core.protocol.Responses.EvaluateResponseBody
import com.microsoft.java.debug.core.protocol.Responses.ScopesResponseBody
import com.microsoft.java.debug.core.protocol.Responses.SetBreakpointsResponseBody
import com.microsoft.java.debug.core.protocol.Responses.StackTraceResponseBody
import com.microsoft.java.debug.core.protocol.Responses.VariablesResponseBody
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.execution.Cancelable
import monix.execution.Scheduler

/**
 * Manages a connection with a debug adapter.
 * It closes the connection after receiving a response to the 'disconnect' request
 */
private[dap] final class DebugAdapterConnection(
    val socket: Socket,
    adapter: DebugAdapterProxy
) extends Closeable {
  // Complete the promise in the background whenever the socket is closed
  val closedPromise: Promise[Unit] = Promise[Unit]()
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

  def evaluate(frameId: Int, expression: String): Task[EvaluateResponseBody] = {
    val arguments = new EvaluateArguments
    arguments.frameId = frameId
    arguments.expression = expression
    adapter.request(Evaluate, arguments)
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

  def launch(noDebug: Boolean): Task[Unit] = {
    val arguments = new LaunchArguments
    arguments.noDebug = noDebug
    adapter.request(Launch, arguments)
  }

  def attach(hostName: String, port: Int): Task[Unit] = {
    val arguments = new AttachArguments
    arguments.hostName = hostName
    arguments.port = port
    adapter.request(Attach, arguments)
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
    socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), 10000)

    val proxy = DebugAdapterProxy(socket)
    proxy.startBackgroundListening(scheduler)
    new DebugAdapterConnection(socket, proxy)
  }
}
