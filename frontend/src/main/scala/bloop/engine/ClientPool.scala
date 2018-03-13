package bloop.engine

import com.martiansoftware.nailgun.{NGClientDisconnectReason, NGClientListener, NGContext}

sealed trait CloseEvent
case object Heartbeat extends CloseEvent
case object SocketError extends CloseEvent
case object SocketTimeout extends CloseEvent
case object SessionShutdown extends CloseEvent
case object InternalError extends CloseEvent

sealed trait ClientPool {
  def addListener(f: CloseEvent => Unit): Unit
  def removeAllListeners(): Unit
}

case object NoPool extends ClientPool {
  override def addListener(f: CloseEvent => Unit): Unit = ()
  override def removeAllListeners(): Unit = ()
}

case class NailgunPool(context: NGContext) extends ClientPool {
  override def addListener(f: CloseEvent => Unit): Unit = {
    val nailgunListener = new NGClientListener {
      override def clientDisconnected(reason: NGClientDisconnectReason): Unit = {
        f(reason match {
          case NGClientDisconnectReason.HEARTBEAT => Heartbeat
          case NGClientDisconnectReason.SOCKET_ERROR => SocketError
          case NGClientDisconnectReason.SOCKET_TIMEOUT => SocketTimeout
          case NGClientDisconnectReason.SESSION_SHUTDOWN => SessionShutdown
          case NGClientDisconnectReason.INTERNAL_ERROR => InternalError
        })
      }
    }
    context.addClientListener(nailgunListener)
    nailgunListener
  }

  override def removeAllListeners(): Unit = context.removeAllClientListeners()
}
