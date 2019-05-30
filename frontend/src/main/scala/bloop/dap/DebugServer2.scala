//package bloop.dap
//
//import java.net._
//import java.util.concurrent.TimeUnit
//
//import bloop.ConnectionHandle
//import monix.eval.Task
//import monix.execution.Scheduler
//
//object DebugServer2 {
//  def create(connection: ConnectionHandle, debuggee: () => Debuggee)(
//      scheduler: Scheduler
//  ): Task[Unit] = {
//    connection.serverSocket.setSoTimeout(TimeUnit.SECONDS.toMillis(5).toInt) // TODO should be done on callsite
//    Runtime.getRuntime.addShutdownHook(new Thread(() => connection.close()))
//
//    def listen(): Task[DebugSession2.ExitStatus] =
//      for {
//        socket <- acceptConnection(connection.serverSocket)
//        exitStatus <- DebugSession2.start(socket, debuggee())(scheduler)
//      } yield exitStatus
//
//    listen().restartUntil(_ == DebugSession2.Terminated).map(_ => ())
//  }
//
//  private def acceptConnection(server: ServerSocket): Task[Socket] = {
//    Task(server.accept()).onErrorRecoverWith { // TODO maybe limit retries
//      case _: SocketTimeoutException => acceptConnection(server)
//    }
//  }
//}
