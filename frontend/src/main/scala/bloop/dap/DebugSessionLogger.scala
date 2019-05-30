package bloop.dap

import java.net.InetSocketAddress

import bloop.dap.DebugSessionLogger.initMessagePrefix
import bloop.logging.{DebugFilter, Logger}
import com.microsoft.java.debug.core.protocol.Events.OutputEvent

import scala.concurrent.Promise

/**
 * Serves two purposes:
 * - forwards the process output to the debug session.
 * - waits for the jdi log to notify the debug session that it can safely attach to the process
 */
final class DebugSessionLogger(
    debugSession: DebugSession,
    addressPromise: Promise[InetSocketAddress]
) extends Logger {
  private var initialized = false
  override val name: String = "DebugSessionLogger"

  override def ansiCodesSupported(): Boolean = false
  override def error(msg: String): Unit = send(msg, OutputEvent.Category.stdout)
  override def info(msg: String): Unit = {
    // since the debuggee a) waits until the debug adapter connects to it and b) is not run in quiet=y mode
    // we can expect the JDI to produce the very first log.
    if (msg.startsWith(initMessagePrefix)) {
      if (!initialized) {
        val port = Integer.parseInt(msg.drop(initMessagePrefix.length))
        val address = new InetSocketAddress(port)
        addressPromise.success(address)
        initialized = true
      }
    } else {
      send(msg, OutputEvent.Category.stderr)
    }
  }

  override def isVerbose: Boolean = false
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit = {}
  override def trace(t: Throwable): Unit = {}
  override def printDebug(msg: String): Unit = {}

  override def asVerbose: Logger = throw UnsupportedException
  override def asDiscrete: Logger = throw UnsupportedException
  override def debugFilter: DebugFilter = throw UnsupportedException
  override def warn(msg: String): Unit = throw UnsupportedException

  private def send(output: String, category: OutputEvent.Category): Unit = {
    val event = new OutputEvent(category, output + System.lineSeparator())
    debugSession.sendEvent(event)
  }

  private def UnsupportedException: Exception = {
    val message = s"$name only supports logging error and info level messages"
    new UnsupportedOperationException(message)
  }
}

object DebugSessionLogger {
  val initMessagePrefix = "Listening for transport dt_socket at address: "
}
