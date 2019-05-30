package bloop.dap

import java.net.{Socket, URI}

import bloop.dap.DebugTestEndpoints._
import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.eval.Task
import monix.execution.Scheduler

/**
 * Manages a connection with a debug adapter.
 * It closes the connection after receiving a response to the 'disconnect' request
 */
private[dap] final class DebugAdapterConnection(socket: Socket)(implicit proxy: DebugSessionProxy) {
  def initialize(): Task[Capabilities] = {
    val arguments = new InitializeArguments()
    Initialize(arguments)
  }

  def configurationDone(): Task[Unit] = {
    ConfigurationDone(())
  }

  def launch(): Task[Unit] = {
    val arguments = new LaunchArguments
    arguments.noDebug = true
    Launch(arguments)
  }

  def disconnect(restart: Boolean): Task[Unit] = {
    val arguments = new DisconnectArguments
    arguments.restart = restart
    for {
      _ <- Disconnect(arguments)
      _ <- Task(socket.close())
    } yield ()
  }

  def exited: Task[Events.ExitedEvent] =
    Exited.first

  def terminated: Task[Events.TerminatedEvent] = {
    Terminated.first
  }

  def output(expected: String): Task[String] = {
    OutputEvent.all.map { events =>
      val builder = new StringBuilder
      events
        .takeWhile(_ => builder.toString() != expected)
        .foreach(e => builder.append(e.output))
      builder.toString()
    }
  }

  def output: Task[String] = {
    OutputEvent.all.map { events =>
      val builder: StringBuilder =
        events.foldLeft(new StringBuilder)((acc, e) => acc.append(e.output))
      builder.toString()
    }
  }
}

object DebugAdapterConnection {
  def connectTo(uri: URI)(scheduler: Scheduler): DebugAdapterConnection = {
    val socket = new Socket(uri.getHost, uri.getPort)
    val proxy = DebugSessionProxy(socket)
    proxy.listen(scheduler)
    new DebugAdapterConnection(socket)(proxy)
  }
}
