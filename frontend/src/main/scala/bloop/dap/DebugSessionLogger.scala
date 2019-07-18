package bloop.dap

import java.net.InetSocketAddress

import bloop.logging.{DebugFilter, Logger}
import com.microsoft.java.debug.core.protocol.Events.OutputEvent

import scala.concurrent.Promise

/**
 * Defines a logger that forwards some events to a debug session. Operations
 * not forwarded to the session are passed onto the underlying logger.
 *
 * A key responsibility of this logger is to intercept the start JDI log and
 * parse the debugging port of the remote machine. This port is then passed to
 * bind the host and remote machines and run the JDI infrastructure.
 */
final class DebugSessionLogger(
    debugSession: DebugSession,
    listener: InetSocketAddress => Unit,
    underlying: Logger
) extends Logger {
  @volatile private var initialized = false
  override val name: String = s"${underlying.name}-debug"

  override def isVerbose: Boolean = underlying.isVerbose
  override def trace(t: Throwable): Unit = underlying.trace(t)
  override def printDebug(msg: String): Unit = underlying.debug(msg)(DebugFilter.All)
  override def warn(msg: String): Unit = underlying.warn(msg)
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit = underlying.debug(msg)(ctx)
  override def ansiCodesSupported(): Boolean = underlying.ansiCodesSupported()

  override def debugFilter: DebugFilter = underlying.debugFilter
  override def asVerbose: Logger =
    new DebugSessionLogger(debugSession, listener, underlying.asVerbose)
  override def asDiscrete: Logger =
    new DebugSessionLogger(debugSession, listener, underlying.asDiscrete)

  override def error(msg: String): Unit = forwardToClient(msg, OutputEvent.Category.stdout)
  override def info(msg: String): Unit = {
    import DebugSessionLogger.ListeningMessagePrefix
    // Expect the first log to be JDI notification since debuggee is running with `quiet=n` JDI option
    if (msg.startsWith(ListeningMessagePrefix)) {
      if (!initialized) {
        val port = Integer.parseInt(msg.drop(ListeningMessagePrefix.length))
        val address = new InetSocketAddress("localhost", port)
        listener(address)
        initialized = true
      }
    } else {
      forwardToClient(msg, OutputEvent.Category.stderr)
    }
  }

  private def forwardToClient(output: String, category: OutputEvent.Category): Unit = {
    val event = new OutputEvent(category, output + System.lineSeparator())
    debugSession.sendEvent(event)
  }
}

object DebugSessionLogger {
  val ListeningMessagePrefix = "Listening for transport dt_socket at address: "
}
