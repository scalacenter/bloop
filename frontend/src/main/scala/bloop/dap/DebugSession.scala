package bloop.dap

import java.net.{InetSocketAddress, Socket}

import com.microsoft.java.debug.core.adapter.{ProtocolServer => DapServer}
import com.microsoft.java.debug.core.protocol.JsonUtils
import com.microsoft.java.debug.core.protocol.Messages.{Request, Response}
import com.microsoft.java.debug.core.protocol.Requests.Command
import com.microsoft.java.debug.core.protocol.Requests.AttachArguments

import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.mutable
import scala.concurrent.Promise

/**
 * Instead of relying on a standard handler for the 'launch' request, this class starts a [[debuggee]] in the background
 * and then attaches to it as if it were a remote process. It also kills the [[debuggee]] upon receiving 'disconnect' request
 */
final class DebugSession(
    socket: Socket,
    debugAddress: Promise[InetSocketAddress],
    ioScheduler: Scheduler
) extends DapServer(socket.getInputStream, socket.getOutputStream, DebugExtensions.newContext) {
  type LaunchId = Int
  private val launches = mutable.Set.empty[LaunchId]

  override def dispatchRequest(request: Request): Unit = {
    val id = request.seq
    request.command match {
      case "launch" =>
        launches.add(id)
        val _ = Task
          .fromFuture(debugAddress.future)
          .map(DebugSession.toAttachRequest(id, _))
          .foreachL(super.dispatchRequest)
          .runAsync(ioScheduler)

      case _ => super.dispatchRequest(request)
    }
  }

  override def sendResponse(response: Response): Unit = {
    val requestSeq = response.request_seq

    response.command match {
      case "attach" if launches(requestSeq) =>
        // Trick dap4j into thinking we're processing a launch instead of attach
        response.command = Command.LAUNCH.getName
        super.sendResponse(response)
      case "disconnect" =>
        super.sendResponse(response)
      case _ =>
        super.sendResponse(response)
    }
  }
}

object DebugSession {
  def open(
      socket: Socket,
      debugAddress: Promise[InetSocketAddress],
      ioScheduler: Scheduler
  ): Task[DebugSession] = {
    for {
      _ <- Task.fromTry(JavaDebugInterface.isAvailable)
    } yield new DebugSession(socket, debugAddress, ioScheduler)
  }

  private[DebugSession] def toAttachRequest(seq: Int, address: InetSocketAddress): Request = {
    val arguments = new AttachArguments
    arguments.hostName = address.getHostName
    arguments.port = address.getPort
    val json = JsonUtils.toJsonTree(arguments, classOf[AttachArguments])
    new Request(seq, Command.ATTACH.getName, json.getAsJsonObject)
  }
}
