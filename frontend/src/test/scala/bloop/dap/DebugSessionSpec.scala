package bloop.dap
import bloop.TestSchedulers
import bloop.testing.BaseSuite
import monix.execution.Scheduler

object DebugSessionSpec extends BaseSuite {
  private implicit val scheduler: Scheduler = TestSchedulers.async("debug-session", threads = 2)

//  test("restarting terminates a debuggee") {
//    val cancelled = Promise[Unit]()
//    def debuggee(session: DebugSession): Task[Unit] = {
//      Task
//        .fromFuture(cancelled.future) // run until cancelled
//        .doOnCancel(Task(cancelled.success(())))
//    }
//
//    val tcp = ConnectionHandle.tcp(backlog = 2)
//    val server = DebugServer2.create(tcp, () => new Debuggee(debuggee))(scheduler)
//    server.executeOn(scheduler).runAsync(scheduler)
//
//    val client = DebugTestClient.apply(tcp.uri)(scheduler)
//    val cancellation = for {
//      _ <- client.restart()
//      _ <- Task.fromFuture(cancelled.future)
//    } yield ()
//
//    TestUtil.await(FiniteDuration(15, TimeUnit.SECONDS))(cancellation)
//  }
}
