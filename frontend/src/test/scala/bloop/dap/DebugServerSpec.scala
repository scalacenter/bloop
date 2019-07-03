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

  test("closes server connection") {
    val listening = Promise[Boolean]()
    val handle = ServerHandle.Tcp()
    val serve: DebugServer = ignored => Task.now(())
    val server = DebugServer.listenTo(handle, serve, scheduler, listening)

    val test = for {
      serverTask <- Task(server.runAsync(scheduler))
      _ <- Task(listening)
      _ <- Task(serverTask.cancel())
      isClosed <- Task(await(handle.server.isClosed))
    } yield {
      assert(isClosed)
    }

    TestUtil.await(FiniteDuration(5, TimeUnit.SECONDS))(test)
  }

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
        val testState = state.toTestState
        val serve: DebugServer = MainClassDebugServer(
          Seq(testState.getProjectFor(project)),
          new ScalaMainClass("Main", Nil, Nil),
          testState.state
        ) match {
          case Right(value) => value
          case Left(error) => throw new Exception(error)
        }

        val handle = ServerHandle.Tcp()
        val listening = Promise[Boolean]()

        val server = DebugServer.listenTo(handle, serve, scheduler, listening)

        val test = for {
          serverTask <- Task(server.runAsync(scheduler))
          _ <- Task.fromFuture(listening.future)
          client = debugAdapter(handle)
          _ <- client.initialize()
          _ <- client.launch()
          _ <- client.configurationDone()
          _ <- Task.eval(serverTask.cancel())
          _ <- client.exited
          _ <- client.terminated
          isClosed <- Task(await(client.socket.isClosed))
        } yield {
          assert(isClosed)
        }

        TestUtil.await(FiniteDuration(5, TimeUnit.SECONDS))(test)
      }
    }
  }

  test("sends exit and terminate events when cannot run debuggee") {
    TestUtil.withinWorkspace { workspace =>
      // note that there is nothing that can be run (no sources)
      val project = TestProject(workspace, "p", Nil)

      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, List(project), logger) { state =>
        val testState = state.toTestState
        val serve: DebugServer = MainClassDebugServer(
          Seq(testState.getProjectFor(project)),
          new ScalaMainClass("Main", Nil, Nil),
          testState.state
        ) match {
          case Right(value) => value
          case Left(error) => throw new Exception(error)
        }

        val handle = ServerHandle.Tcp()
        val listening = Promise[Boolean]()

        val server = DebugServer.listenTo(handle, serve, scheduler, listening)

        val test = for {
          _ <- Task(server.runAsync(scheduler))
          _ <- Task.fromFuture(listening.future)
          client = debugAdapter(handle)
          _ <- client.initialize()
          _ <- client.launch()
          _ <- client.configurationDone()
          _ <- client.exited
          _ <- client.terminated
          _ <- client.disconnect(restart = false)
          isClosed <- Task(await(client.socket.isClosed))
        } yield {
          assert(isClosed)
        }

        TestUtil.await(FiniteDuration(5, TimeUnit.SECONDS))(test)
      }
    }
  }
  @tailrec
  private def await(condition: => Boolean): Boolean = {
    if (condition) true
    else {
      Thread.sleep(250)
      await(condition)
    }
  }

  private def debugAdapter(handle: ServerHandle): DebugAdapterConnection = {
    DebugAdapterConnection.connectTo(handle.uri)(scheduler)
  }
}
