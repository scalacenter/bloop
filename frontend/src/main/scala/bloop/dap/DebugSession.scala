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
 * Instead of relying on a standard handler for the 'launch' request, this class starts a [[debuggee]] in the background
 * and then attaches to it as if it were a remote process. It also kills the [[debuggee]] upon receiving 'disconnect' request
 */
final class DebugSession(socket: Socket, debuggee: Debuggee, ctx: IProviderContext)(
    ioScheduler: Scheduler
) extends ProtocolServer(socket.getInputStream, socket.getOutputStream, ctx) {
  private val launches = mutable.Set.empty[Int]

  def bindDebuggeeAddress(address: InetSocketAddress): Unit = {
    debuggee.bind(address)
  }

  override def dispatchRequest(request: Request): Unit = {
    val seq = request.seq

    request.command match {
      case "launch" =>
        launches.add(seq) // so that we can modify the response by changing its command type from 'attach' to 'launch'
        debuggee
          .start(this)
          .map(attachRequest(seq, _))
          .foreach(super.dispatchRequest)(ioScheduler)

      case _ =>
        super.dispatchRequest(request)
    }
  }

  override def sendResponse(response: Response): Unit = {
    val requestSeq = response.request_seq

    response.command match {
      case "attach" if launches(requestSeq) =>
        response.command = LAUNCH.getName // pretend we are responding to the launch request
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
