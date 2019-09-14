package bloop.dap

import bloop.dap.DebugTestProtocol._
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.{Events, Types}

private[dap] object DebugTesEndpoints {
  val Initialize = new Request[InitializeArguments, Types.Capabilities]("initialize")
  val Launch = new Request[LaunchArguments, Unit]("launch")
  val Disconnect = new Request[DisconnectArguments, Unit]("disconnect")
  val ConfigurationDone = new Request[Unit, Unit]("configurationDone")

  val Exited = new Event[Events.ExitedEvent]("exited")
  val Initialized = new Event[Events.InitializedEvent]("initialized")
  val Terminated = new Event[Events.TerminatedEvent]("terminated")
  val OutputEvent = new Event[Events.OutputEvent]("output")
}
