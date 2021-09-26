package bloop.dap

import bloop.dap.DebugTestProtocol._
import com.microsoft.java.debug.core.protocol.Requests._
import com.microsoft.java.debug.core.protocol.{Events, Types}
import com.microsoft.java.debug.core.protocol.Responses.SetBreakpointsResponseBody
import com.microsoft.java.debug.core.protocol.Responses.ContinueResponseBody
import com.microsoft.java.debug.core.protocol.Responses.ScopesResponseBody
import com.microsoft.java.debug.core.protocol.Responses.StackTraceResponseBody
import com.microsoft.java.debug.core.protocol.Responses.VariablesResponseBody
import com.microsoft.java.debug.core.protocol.Responses.EvaluateResponseBody

private[dap] object DebugTestEndpoints {
  val Initialize = new Request[InitializeArguments, Types.Capabilities]("initialize")
  val Launch = new Request[LaunchArguments, Unit]("launch")
  val Attach = new Request[AttachArguments, Unit]("attach")
  val Disconnect = new Request[DisconnectArguments, Unit]("disconnect")
  val SetBreakpoints =
    new Request[SetBreakpointArguments, SetBreakpointsResponseBody]("setBreakpoints")
  val StackTrace = new Request[StackTraceArguments, StackTraceResponseBody]("stackTrace")
  val Scopes = new Request[ScopesArguments, ScopesResponseBody]("scopes")
  val Variables = new Request[VariablesArguments, VariablesResponseBody]("variables")
  val Evaluate = new Request[EvaluateArguments, EvaluateResponseBody]("evaluate")
  val Continue = new Request[ContinueArguments, ContinueResponseBody]("continue")
  val ConfigurationDone = new Request[Unit, Unit]("configurationDone")

  val Exited = new Event[Events.ExitedEvent]("exited")
  val Initialized = new Event[Events.InitializedEvent]("initialized")
  val Terminated = new Event[Events.TerminatedEvent]("terminated")
  val StoppedEvent = new Event[Events.StoppedEvent]("stopped")
  val OutputEvent = new Event[Events.OutputEvent]("output")
}
