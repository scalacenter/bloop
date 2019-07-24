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

  def initialize(): Task[Response[Capabilities]] =
    activeSession.initialize()

  def configurationDone(): Task[Response[Unit]] =
    activeSession.configurationDone()

  def launch(): Task[Response[Unit]] =
    activeSession.launch()

  def exited: Task[Events.ExitedEvent] =
    activeSession.exited

  def terminated: Task[Events.TerminatedEvent] = {
    activeSession.terminated
  }

  def output(expected: String): Task[String] = {
    activeSession.output(expected)
  }

  def firstOutput: Task[String] = {
    activeSession.firstOutput
  }

  def allOutput: Task[String] = {
    activeSession.allOutput
  }

  def disconnect(): Task[Response[Unit]] = {
    activeSession.disconnect(restart = false)
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
