package bloop.dap

import java.net.{Socket, URI}

import bloop.dap.DebugTestEndpoints._
import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.eval.Task
import monix.execution.Scheduler

final class DebugTestClient(socket: Socket)(implicit proxy: DebugSessionProxy) {
  def initialize(): Task[Capabilities] = {
    val arguments = new InitializeArguments()
    Initialize(arguments)
  }

  def configurationDone(): Task[Unit] = {
    ConfigurationDone(())
  }

  def launch(): Task[Unit] = {
    Launch(new LaunchArguments)
  }

  def restart(): Task[Unit] = {
    val arguments = new DisconnectArguments
    arguments.restart = true
    Disconnect(arguments)
  }

  def disconnect(): Task[Unit] = {
    val arguments = new DisconnectArguments
    Disconnect(arguments).map(_ => socket.close())
  }

  def initialized: Task[Events.InitializedEvent] = {
    Initialized.first
  }

  def exited: Task[Events.ExitedEvent] =
    Exited.first

  def terminated: Task[Events.TerminatedEvent] = {
    Terminated.first
  }

  def lines(n: Int): Task[String] = {
    OutputEvent.all
      .take(n)
      .foldLeftL(new StringBuilder)((acc, e) => acc.append(e.output))
      .map(_.toString())
  }

  def output(expected: String): Task[String] = {
    OutputEvent.all
      .foldWhileL(new StringBuilder)((acc, e) => {
        acc.append(e.output)
        (acc.toString() != expected, acc)
      })
      .map(_.toString())
  }

  /**
   * Must be executed **after** the [[disconnect]] to ensure the stream of events is finite
   */
  def output(): Task[String] = {
    OutputEvent.all
      .foldLeftL(new StringBuilder)((acc, e) => acc.append(e.output))
      .map(_.toString())
  }
}

object DebugTestClient {
  def apply(uri: URI)(scheduler: Scheduler): DebugTestClient = {
    val socket = new Socket(uri.getHost, uri.getPort)

    val proxy = DebugSessionProxy(socket)
    proxy.listen(scheduler)

    new DebugTestClient(socket)(proxy)
  }
}
