package bloop.dap

import java.net.{Socket, URI}

import bloop.dap.DebugTestEndpoints._
import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.eval.Task
import monix.execution.Scheduler

/**
 * Handles the debug session by:
 *  1) forwarding the communication
 * 2) restarting the session (by terminating old session and opening a new one)
 */
final class DebugTestClient(connect: () => DebugAdapterConnection) {
  private var session = connect()
  def initialize(): Task[Capabilities] =
    session.initialize()

  def configurationDone(): Task[Unit] =
    session.configurationDone()

  def launch(): Task[Unit] =
    session.launch()

  def exited: Task[Events.ExitedEvent] =
    session.exited

  def terminated: Task[Events.TerminatedEvent] = {
    session.terminated
  }

  def output(expected: String): Task[String] = {
    session.output(expected)
  }

  def firstOutput: Task[String] = {
    session.firstOutput
  }

  def allOutput: Task[String] = {
    session.allOutput
  }

  def disconnect(): Task[Unit] = {
    session.disconnect(restart = false)
  }

  def restart(): Task[DebugAdapterConnection] = {
    for {
      _ <- session.disconnect(restart = true)
      previousSession <- reconnect()
    } yield previousSession
  }

  private def reconnect(): Task[DebugAdapterConnection] = {
    Task {
      val previousSession = session
      session = connect()
      previousSession
    }
  }
}

object DebugTestClient {
  def apply(uri: URI)(scheduler: Scheduler): DebugTestClient = {
    new DebugTestClient(() => DebugAdapterConnection.connectTo(uri)(scheduler))
  }
}
