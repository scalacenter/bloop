package bloop.dap
import java.net.{ConnectException, URI}
import java.util.concurrent.TimeUnit

import bloop.TestSchedulers
import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task

import scala.annotation.tailrec
import scala.concurrent.TimeoutException
import scala.concurrent.duration.FiniteDuration
import scala.util.{Failure, Try}

object DebugServerSpec extends BspBaseSuite {
  override val protocol: BspProtocol.Local.type = BspProtocol.Local
  private val scheduler = TestSchedulers.async("debug-server", 4)

  test("closes server connection") {
    val adapter: DebugAdapter = ignored => Task.now(())
    val server = DebugServer.create(adapter, scheduler)

    def connectionRefused(uri: URI): Boolean =
      Try(connectToDebugAdapter(uri)) match {
        case Failure(e: ConnectException) =>
          e.getMessage == "Connection refused (Connection refused)"
        case _ => false
      }

    val test = for {
      uri <- start(server)
      _ <- Task(server.cancel())
      couldNotConnect <- Task(await(connectionRefused(uri)))
    } yield {
      assert(couldNotConnect)
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
        val adapter: DebugAdapter = createDebugAdapter(project, state)
        val server = DebugServer.create(adapter, scheduler)

        val test = for {
          uri <- start(server)
          client = connectToDebugAdapter(uri)
          _ <- client.initialize()
          _ <- client.launch()
          _ <- client.configurationDone()
          _ <- Task(server.cancel())
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
        val adapter: DebugAdapter = createDebugAdapter(project, state)
        val server = DebugServer.create(adapter, scheduler)

        val test = for {
          uri <- start(server)
          client = connectToDebugAdapter(uri)
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

  test("does not accept a connection unless the previous session requests a restart") {
    val adapter: DebugAdapter = ignored => Task.now(())
    val server = DebugServer.create(adapter, scheduler)

    val test = for {
      uri <- start(server)
      firstClient <- Task(connectToDebugAdapter(uri))
      secondClient <- Task(connectToDebugAdapter(uri))
      beforeRestart = Try(
        TestUtil.await(FiniteDuration(1, TimeUnit.SECONDS))(secondClient.initialize())
      )
      _ <- firstClient.disconnect(restart = true)
      afterRestart = Try(
        TestUtil.await(FiniteDuration(100, TimeUnit.MILLISECONDS))(secondClient.initialize())
      )
      _ <- Task(server.cancel())
    } yield {
      val firstTryFailedToConnect = beforeRestart match {
        case Failure(ex: TimeoutException) => true
        case _ => false
      }
      assert(firstTryFailedToConnect, afterRestart.isSuccess)
    }

    TestUtil.await(FiniteDuration(5, TimeUnit.SECONDS))(test)
  }

  @tailrec
  private def await(condition: => Boolean): Boolean = {
    if (condition) true
    else {
      Thread.sleep(250)
      await(condition)
    }
  }

  private def start(server: DebugServer): Task[URI] = {
    server.run(scheduler).map {
      case None => throw new IllegalStateException("Server is not listening")
      case Some(uri) => uri
    }
  }

  private def connectToDebugAdapter(uri: URI): DebugAdapterConnection = {
    DebugAdapterConnection.connectTo(uri)(scheduler)
  }

  private def createDebugAdapter(
      project: TestProject,
      state: DebugServerSpec.ManagedBspTestState
  ) = {
    val testState = state.toTestState
    val adapter: DebugAdapter = DebugAdapter.runMainClass(
      Seq(testState.getProjectFor(project)),
      new ScalaMainClass("Main", Nil, Nil),
      testState.state
    ) match {
      case Right(value) => value
      case Left(error) => throw new Exception(error)
    }
    adapter
  }

}
