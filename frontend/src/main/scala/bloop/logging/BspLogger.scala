package bloop.logging

import java.util.concurrent.atomic.AtomicInteger

import bloop.engine.State
import org.langmeta.jsonrpc.JsonRpcClient
import org.langmeta.lsp.Window

/**
 * Creates a logger that will forward all the messages to the underlying bsp client.
 * It does so via the replication of the `window/showMessage` LSP functionality.
 */
final class BspLogger private (
    override val name: String,
    underlying: Logger,
    client: JsonRpcClient,
    ansiSupported: Boolean
) extends Logger {

  override def isVerbose: Boolean = underlying.isVerbose
  override def asDiscrete: Logger =
    new BspLogger(name, underlying.asDiscrete, client, ansiSupported)
  override def asVerbose: Logger =
    new BspLogger(name, underlying.asVerbose, client, ansiSupported)

  override def ansiCodesSupported: Boolean = ansiSupported || underlying.ansiCodesSupported()
  override def debug(msg: String): Unit = underlying.debug(msg)
  override def trace(t: Throwable): Unit = underlying.trace(t)

  override def error(msg: String): Unit = {
    underlying.error(msg)
    Window.showMessage.error(msg)(client)
  }

  override def warn(msg: String): Unit = {
    underlying.warn(msg)
    Window.showMessage.warn(msg)(client)
  }

  override def info(msg: String): Unit = {
    underlying.info(msg)
    Window.showMessage.info(msg)(client)
  }
}

object BspLogger {
  private[bloop] final val counter: AtomicInteger = new AtomicInteger(0)

  def apply(state: State, client: JsonRpcClient, ansiCodesSupported: Boolean): BspLogger = {
    val name: String = s"bsp-logger-${BspLogger.counter.incrementAndGet()}"
    new BspLogger(name, state.logger, client, ansiCodesSupported)
  }
}
