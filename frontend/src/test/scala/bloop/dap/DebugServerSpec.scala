package bloop.dap
import java.util.concurrent.TimeUnit

import bloop.TestSchedulers
import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.io.ServerHandle
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task

import scala.annotation.tailrec
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration

object DebugServerSpec extends BspBaseSuite {
  override val protocol: BspProtocol.Local.type = BspProtocol.Local
  private val scheduler = TestSchedulers.async("debug-server", 4)

  test("closes active sessions when closed") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    while(true) sleep(10000) // block for all eternity
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val serve: DebugServer = MainClassDebugServer(
          Seq(state.toTestState.getProjectFor(project)),
          new ScalaMainClass("Main", Nil, Nil),
          state.toTestState.state
        ) match {
          case Right(value) => value
          case Left(error) => throw new Exception(error)
        }

        val handle = ServerHandle.Tcp()
        val listening = Promise[Boolean]()

        val server = DebugServer.listenTo(handle, serve, scheduler, listening)

        val task = for {
          serverTask <- Task(server.runAsync(scheduler))
          _ <- Task.fromFuture(listening.future)
          client = debugAdapter(handle)
          _ <- client.initialize()
          _ <- client.launch()
          _ <- client.configurationDone()
          _ <- Task.eval(serverTask.cancel()) //
          _ <- Task(println("test:canceled"))
          _ <- client.exited
          _ <- Task(println("test:exited"))
          _ <- client.terminated
          _ <- Task(println("test:terminated"))
          isClosed <- Task(awaitSocketClosed(client))
          _ <- Task(println(s"test:$isClosed"))
        } yield {
          assert(isClosed)
        }

        TestUtil.await(FiniteDuration(30, TimeUnit.SECONDS))(task)
      }
    }

  }

  @tailrec
  private def awaitSocketClosed(client: DebugAdapterConnection): Boolean = {
    if (client.socket.isClosed) true
    else {
      Thread.sleep(250)
      awaitSocketClosed(client)
    }
  }

  private def debugAdapter(handle: ServerHandle): DebugAdapterConnection = {
    DebugAdapterConnection.connectTo(handle.uri)(scheduler)
  }
}
