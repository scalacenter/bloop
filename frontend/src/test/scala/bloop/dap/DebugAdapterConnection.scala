package bloop.dap

import java.net.{InetSocketAddress, Socket, URI}

import bloop.dap.DebugTesEndpoints._
import com.microsoft.java.debug.core.protocol.Events
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.Types.Capabilities
import monix.eval.Task
import monix.execution.Scheduler

/**
 * Manages a connection with a debug adapter.
 * It closes the connection after receiving a response to the 'disconnect' request
 */
private[dap] final class DebugAdapterConnection(val socket: Socket, adapter: DebugAdapterProxy) {
  def initialize(): Task[Capabilities] = {
    val arguments = new InitializeArguments()
    adapter.request(Initialize, arguments)
  }

  def configurationDone(): Task[Unit] = {
    adapter.request(ConfigurationDone, ())
  }

  def launch(): Task[Unit] = {
    val arguments = new LaunchArguments
    arguments.noDebug = true
    adapter.request(Launch, arguments)
  }

  def disconnect(restart: Boolean): Task[Unit] = {
    val arguments = new DisconnectArguments
    arguments.restart = restart
    for {
      _ <- adapter.request(Disconnect, arguments)
      _ <- Task(adapter.close())
    } yield ()
  }

  def exited: Task[Events.ExitedEvent] = {
    adapter.events.first(Exited)
  }

  def terminated: Task[Events.TerminatedEvent] = {
    adapter.events.first(Terminated)
  }

  def output(expected: String): Task[String] = {
    adapter.events.all(OutputEvent).map { events =>
      val builder = new StringBuilder
      events
        .takeWhile(_ => builder.toString() != expected)
        .foreach(e => builder.append(e.output))
      builder.toString()
    }
  }

  def firstOutput: Task[String] = {
    adapter.events.first(OutputEvent).map(_.output)
  }

  def allOutput: Task[String] = {
    adapter.events.all(OutputEvent).map { events =>
      val builder: StringBuilder =
        events.foldLeft(new StringBuilder)((acc, e) => acc.append(e.output))
      builder.toString()
    }
  }
}

object DebugAdapterConnection {
  def connectTo(uri: URI)(scheduler: Scheduler): DebugAdapterConnection = {
    val socket = new Socket() // create unconnected socket
    socket.connect(new InetSocketAddress(uri.getHost, uri.getPort), 500)

    val proxy = DebugAdapterProxy(socket)
    proxy.startBackgroundListening(scheduler)
    new DebugAdapterConnection(socket, proxy)
  }
}
