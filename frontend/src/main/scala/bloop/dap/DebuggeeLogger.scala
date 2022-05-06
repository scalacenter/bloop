package bloop.dap

import java.net.InetSocketAddress

import ch.epfl.scala.debugadapter.DebuggeeListener

import bloop.dap.DebuggeeLogger.JDINotificationPrefix
import bloop.logging.DebugFilter
import bloop.logging.Logger

import monix.execution.atomic.Atomic

object DebuggeeLogger {
  final val JDINotificationPrefix = "Listening for transport dt_socket at address: "
}

class DebuggeeLogger(listener: DebuggeeListener, underlying: Logger) extends Logger {
  private val initialized = Atomic(false)
  override val name: String = s"${underlying.name}-debug"

  override def isVerbose: Boolean = underlying.isVerbose
  override def trace(t: Throwable): Unit = underlying.trace(t)
  override def printDebug(msg: String): Unit = underlying.debug(msg)(DebugFilter.All)
  override def warn(msg: String): Unit = underlying.warn(msg)
  override def debug(msg: String)(implicit ctx: DebugFilter): Unit = underlying.debug(msg)(ctx)
  override def ansiCodesSupported(): Boolean = underlying.ansiCodesSupported()

  override def debugFilter: DebugFilter = underlying.debugFilter
  override def asVerbose: Logger =
    new DebuggeeLogger(listener, underlying.asVerbose)
  override def asDiscrete: Logger =
    new DebuggeeLogger(listener, underlying.asDiscrete)
  override def withOriginId(originId: Option[String]): Logger =
    new DebuggeeLogger(listener, underlying.withOriginId(originId))

  override def error(msg: String): Unit = {
    underlying.error(msg)
    listener.err(msg)
  }

  override def info(msg: String): Unit = {
    // don't log program output by default, since it can be long
    underlying.debug(msg)(debugFilter)
    // Expect the first log to be JDI notification since debuggee runs with `quiet=n` JDI option
    if (msg.startsWith(JDINotificationPrefix)) {
      if (initialized.compareAndSet(false, true)) {
        val port = Integer.parseInt(msg.drop(JDINotificationPrefix.length))
        val address = new InetSocketAddress("127.0.0.1", port)
        listener.onListening(address)
      }
    } else {
      listener.out(msg)
    }
  }
}
