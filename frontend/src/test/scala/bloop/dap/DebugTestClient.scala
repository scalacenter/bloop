package bloop.dap

import java.net.URI

import bloop.dap.DebugTestProtocol.Response
import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.eval.Task
import monix.execution.Scheduler

/**
 * Allows communication with the active debug session as well as transparent restarting of the session (see [[restart()]])
 */
final class DebugTestClient(connect: () => DebugAdapterConnection) {

  /**
   * Active debug session.
   * Gets replaced whenever test client restarts.
   */
  private var activeSession = connect()

  def initialize(): Task[Capabilities] =
    activeSession.initialize()

  def initialized: Task[Events.InitializedEvent] =
    activeSession.initialized

  def configurationDone(): Task[Unit] =
    activeSession.configurationDone()

  def launch(): Task[Unit] =
    activeSession.launch()

  def exited: Task[Events.ExitedEvent] =
    activeSession.exited

  def terminated: Task[Events.TerminatedEvent] = {
    activeSession.terminated
  }

  def takeCurrentOutput: Task[String] = {
    activeSession.takeCurrentOutput
  }

  def blockForAllOutput: Task[String] = {
    activeSession.blockForAllOutput
  }

  def disconnect(): Task[Unit] = {
    activeSession.disconnect(restart = false)
  }

  import DebugTestProtocol.Response
  def printCapabilities(response: Response[Capabilities]): Unit = {
    response match {
      case Response.Failure(message) => sys.error(message)
      case Response.Success(capabilities) =>
        pprint.log(capabilities.supportsConfigurationDoneRequest)
        pprint.log(capabilities.supportsHitConditionalBreakpoints)
        pprint.log(capabilities.supportsConditionalBreakpoints)
        pprint.log(capabilities.supportsEvaluateForHovers)
        pprint.log(capabilities.supportsCompletionsRequest)
        pprint.log(capabilities.supportsRestartFrame)
        pprint.log(capabilities.supportsSetVariable)
        pprint.log(capabilities.supportsRestartRequest)
        pprint.log(capabilities.supportTerminateDebuggee)
        pprint.log(capabilities.supportsDelayedStackTraceLoading)
        pprint.log(capabilities.supportsLogPoints)
        pprint.log(capabilities.supportsExceptionInfoRequest)
    }

  }

  /**
   * First, disconnects from the active session and then establishes a new connection
   */
  def restart(): Task[DebugAdapterConnection] = {
    for {
      _ <- activeSession.disconnect(restart = true)
      previousSession <- reconnect()
    } yield previousSession
  }

  private def reconnect(): Task[DebugAdapterConnection] = {
    Task {
      val previousSession = activeSession
      activeSession = connect()
      previousSession
    }
  }
}

object DebugTestClient {
  def apply(uri: URI)(scheduler: Scheduler): DebugTestClient = {
    new DebugTestClient(() => DebugAdapterConnection.connectTo(uri)(scheduler))
  }
}
