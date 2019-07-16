package bloop.dap

import java.net.{InetSocketAddress, Socket}

import bloop.dap.DebugSession._

import com.microsoft.java.debug.core.adapter._
import com.microsoft.java.debug.core.protocol.JsonUtils
import com.microsoft.java.debug.core.protocol.Messages._
import com.microsoft.java.debug.core.protocol.Requests.Command._
import com.microsoft.java.debug.core.protocol.Requests._

import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.mutable

/**
 * A debug session sets up a session with the Java debug protocol and acts as a
 * proxy to the internal Microsoft Java debug implementation.
 *
 * The debug session is mapped to a socket and a debuggee and it proxies any
 * debug-related request and response. Because our internal architecture
 * doesn't support 'launch' requests, we transform them to attach requests by
 * first starting a debuggee in the background and then sending an attach
 * request to the Java debug implementation. Then, we map back responses to
 * that attach request to the original launch request so that this
 * under-the-hood transformation is transparent to the client. When the
 * disconnect request is received, this session is responsible of cancelling
 * the background debuggee process.
 */
final class DebugSession(socket: Socket, debuggee: Debuggee, ctx: IProviderContext)(
    ioScheduler: Scheduler
) extends ProtocolServer(socket.getInputStream, socket.getOutputStream, ctx) {
  /* Holds client launch request IDs to map responses from JDI to requests */
  private val launches = mutable.Set.empty[Int]

  def bindDebuggeeAddress(address: InetSocketAddress): Unit = {
    debuggee.bind(address)
  }

  override def dispatchRequest(request: Request): Unit = {
    val id = request.seq
    request.command match {
      case "launch" =>
        launches.add(id)
        debuggee
          .start(this)
          .map(attachRequest(id, _))
          .foreach(super.dispatchRequest)(ioScheduler)
        ()

      case _ =>
        super.dispatchRequest(request)
    }
  }

  override def sendResponse(response: Response): Unit = {
    val requestId = response.request_seq
    response.command match {
      case "attach" if launches(requestId) =>
        response.command = LAUNCH.getName
        super.sendResponse(response)
      case "disconnect" =>
        super.sendResponse(response)
        debuggee.cancel()
      case _ =>
        super.sendResponse(response)
    }
  }
}

object DebugSession {
  def open(socket: Socket, debuggee: Debuggee)(ioScheduler: Scheduler): Task[DebugSession] = {
    for {
      _ <- Task.fromTry(JavaDebugInterface.isAvailable)
      ctx = DebugExtensions.createContext()
    } yield new DebugSession(socket, debuggee, ctx)(ioScheduler)
  }

  def attachRequest(seq: Int, address: InetSocketAddress): Request = {
    val arguments = new AttachArguments
    arguments.hostName = address.getHostName
    arguments.port = address.getPort

    val json = JsonUtils.toJsonTree(arguments, classOf[AttachArguments])
    new Request(seq, ATTACH.getName, json.getAsJsonObject)
  }
}
