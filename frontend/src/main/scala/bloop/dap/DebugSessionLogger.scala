package bloop.dap

import java.net.InetSocketAddress

import bloop.logging.{DebugFilter, Logger}
import com.microsoft.java.debug.core.protocol.Events.OutputEvent
import monix.execution.atomic.Atomic

/**
 * Defines a logger that forwards some events to a debug session
 * in addition to sending all of them to the underlying logger.
 *
 * A key responsibility of this logger is to intercept the initial JDI log and
 * parse the debugging port of the remote machine. This port is then passed to
 * bind the host and remote machines and run the JDI infrastructure.
 */
final class DebugSessionLogger(
    debugSession: DebugSession,
    listener: InetSocketAddress => Unit,
    underlying: Logger,
    initialized0: Option[Atomic[Boolean]] = None
) extends Logger {
  private val initialized = initialized0.getOrElse(Atomic(false))
  override val name: String = s"${underlying.name}-debug"

  override def isVerbose: Boolean = underlying.isVerbose
  override def trace(t: Throwable): Unit = underlying.trace(t)
  override def printDebug(msg: String): Unit = underlying.debug(msg)(DebugFilter.All)
  override def warn(msg: String): Unit = underlying.warn(msg)
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit = underlying.debug(msg)(ctx)
  override def ansiCodesSupported(): Boolean = underlying.ansiCodesSupported()

  override def debugFilter: DebugFilter = underlying.debugFilter
  override def asVerbose: Logger =
    new DebugSessionLogger(debugSession, listener, underlying.asVerbose, Some(initialized))
  override def asDiscrete: Logger =
    new DebugSessionLogger(debugSession, listener, underlying.asDiscrete, Some(initialized))
  override def withOriginId(originId: Option[String]): Logger = {
    new DebugSessionLogger(
      debugSession,
      listener,
      underlying.withOriginId(originId),
      Some(initialized)
    )
  }

  override def error(msg: String): Unit = {
    underlying.error(msg)
    forwardToDebugClient(msg, OutputEvent.Category.stderr)
  }

  override def info(msg: String): Unit = {
    underlying.info(msg)

    import DebugSessionLogger.JDINotificationPrefix
    // Expect the first log to be JDI notification since debuggee runs with `quiet=n` JDI option
    if (msg.startsWith(JDINotificationPrefix)) {
      if (initialized.compareAndSet(false, true)) {
        val port = Integer.parseInt(msg.drop(JDINotificationPrefix.length))
        val address = new InetSocketAddress("127.0.0.1", port)
        listener(address)
      }
    } else {
      forwardToDebugClient(msg, OutputEvent.Category.stdout)
    }
  }

  private def forwardToDebugClient(output: String, category: OutputEvent.Category): Unit = {
    val event = new OutputEvent(category, output + System.lineSeparator())
    debugSession.sendEvent(event)
  }
}

object DebugSessionLogger {
  val JDINotificationPrefix = "Listening for transport dt_socket at address: "
}
