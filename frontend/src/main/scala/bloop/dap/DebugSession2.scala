//package bloop.dap
//
//import java.net.{InetSocketAddress, Socket}
//
//import bloop.dap.DebugSession2._
//import com.microsoft.java.debug.core.adapter._
//import com.microsoft.java.debug.core.protocol.JsonUtils
//import com.microsoft.java.debug.core.protocol.Messages._
//import com.microsoft.java.debug.core.protocol.Requests.Command._
//import com.microsoft.java.debug.core.protocol.Requests._
//import monix.eval.Task
//import monix.execution.Scheduler
//
//import scala.collection.mutable
//import scala.concurrent.Promise
//
///**
// * Instead of relying on a standard handler for the 'launch' request, this class starts a [[debuggee]] in the background
// * and then attaches to it as if it were a remote process. It also kills the [[debuggee]] upon receiving 'disconnect' request
// */
//final class DebugSession2(socket: Socket, debuggee: Debuggee, ctx: IProviderContext)(
//    ioScheduler: Scheduler
//) extends ProtocolServer(socket.getInputStream, socket.getOutputStream, ctx) {
//  private val launches = mutable.Set.empty[Int]
//  private val exitStatusPromise = Promise[ExitStatus]()
//
//  def exitStatus: Task[ExitStatus] = {
//    Task.fromFuture(exitStatusPromise.future)
//  }
//
//  override def run(): Unit = {
//    debuggee.start(this) // start the debuggee as soon as possible
//    super.run()
//    exitStatusPromise.trySuccess(Terminated)
//  }
//
//  def bindDebuggeeAddress(address: InetSocketAddress): Unit = {
//    debuggee.bind(address)
//  }
//
//  override def dispatchRequest(request: Request): Unit = synchronized {
//    val seq = request.seq
//
//    request.command match {
//      case "attach" =>
//        val res = new Response(request.seq, request.command, false, "Attaching is not supported")
//        this.sendResponse(res)
//      case "launch" =>
//        launches
//          .add(seq) // so that we can modify the response by changing its command type from 'attach' to 'launch'
//        debuggee.address
//          .map(attachRequest(seq, _))
//          .foreachL(super.dispatchRequest)
//          .runAsync(ioScheduler)
//
//      case "disconnect" | "terminate" =>
//        if (request.arguments.get("restart").getAsBoolean) {
//          exitStatusPromise.success(Restarted)
//        }
//        super.dispatchRequest(request)
//      case _ =>
//        super.dispatchRequest(request)
//    }
//  }
//
//  override def sendResponse(response: Response): Unit = {
//    val requestSeq = response.request_seq
//
//    response.command match {
//      case "attach" if launches(requestSeq) =>
//        response.command = LAUNCH.getName // pretend we are responding to the launch request
//        super.sendResponse(response)
//      case "disconnect" | "terminate" =>
//        super.sendResponse(response)
//        debuggee.cancel()
//      case _ =>
//        super.sendResponse(response)
//    }
//  }
//}
//
//object DebugSession2 {
//  trait ExitStatus
//  case object Restarted extends ExitStatus
//  case object Terminated extends ExitStatus
//
//  def start(socket: Socket, debuggee: Debuggee)(ioScheduler: Scheduler): Task[ExitStatus] = {
//    val session = open(socket, debuggee)(ioScheduler)
//    session.flatMap { session =>
//      ioScheduler.executeAsync(() => session.run())
//      session.exitStatus
//    }
//  }
//
//  def open(socket: Socket, debuggee: Debuggee)(ioScheduler: Scheduler): Task[DebugSession2] = {
//    for {
//      _ <- Task.fromTry(JavaDebugInterface.isAvailable)
//      ctx = DebugExtensions.createContext()
//    } yield new DebugSession2(socket, debuggee, ctx)(ioScheduler)
//  }
//
//  def attachRequest(seq: Int, address: InetSocketAddress): Request = {
//    val arguments = new AttachArguments
//    arguments.hostName = address.getHostName
//    arguments.port = address.getPort
//
//    val json = JsonUtils.toJsonTree(arguments, classOf[AttachArguments])
//    new Request(seq, ATTACH.getName, json.getAsJsonObject)
//  }
//}
