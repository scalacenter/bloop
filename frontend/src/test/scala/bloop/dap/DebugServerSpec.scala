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
import monix.execution.Cancelable

import scala.annotation.tailrec
import scala.collection.concurrent.TrieMap
import scala.concurrent.{Promise, TimeoutException}
import scala.util.{Failure, Try}

object DebugServerSpec extends BspBaseSuite {
  override val protocol: BspProtocol.Local.type = BspProtocol.Local
  private val scheduler = TestSchedulers.async("debug-server", 8)

  private val servers = TrieMap.empty[DebugServer, Cancelable]
  private def logger = new RecordingLogger(ansiCodesSupported = false)

  test("closes server connection") {
    val runner: DebuggeeRunner = _ => Task.now(())
    val server = DebugServer.create(runner, scheduler, logger)

    val test = for {
      uri <- start(server)
      _ <- stop(server)
      couldNotConnect <- Task(await(connectionRefused(uri)))
    } yield {
      assert(couldNotConnect)
    }

    TestUtil.await(5, TimeUnit.SECONDS)(test)
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
        val runner = runMain(project, state)
        val server = DebugServer.create(runner, scheduler, logger)

        val test = for {
          uri <- start(server)
          client = connectToDebugAdapter(uri)
          _ <- client.initialize()
          _ <- client.launch()
          _ <- client.configurationDone()
          _ <- stop(server)
          _ <- client.exited
          _ <- client.terminated
          isClosed <- Task(await(client.socket.isClosed))
        } yield {
          assert(isClosed)
        }

        TestUtil.await(5, TimeUnit.SECONDS)(test)
      }
    }
  }

  test("sends exit and terminate events when cannot run debuggee") {
    TestUtil.withinWorkspace { workspace =>
      // note that there is nothing that can be run (no sources)
      val project = TestProject(workspace, "p", Nil)

      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, List(project), logger) { state =>
        val runner = runMain(project, state)
        val server = DebugServer.create(runner, scheduler, logger)

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

        TestUtil.await(5, TimeUnit.SECONDS)(test)
      }
    }
  }

  test("does not accept a connection unless the previous session requests a restart") {
    val runner: DebuggeeRunner = _ => Task.now(())
    val server = DebugServer.create(runner, scheduler, logger)

    val test = for {
      uri <- start(server)
      firstClient <- Task(connectToDebugAdapter(uri))
      secondClient <- Task(connectToDebugAdapter(uri))
      beforeRestart = Try(TestUtil.await(1, TimeUnit.SECONDS)(secondClient.initialize()))
      _ <- firstClient.disconnect(restart = true)
      afterRestart = Try(TestUtil.await(100, TimeUnit.MILLISECONDS)(secondClient.initialize()))
      _ <- stop(server)
    } yield {
      val firstTryFailedToConnect = beforeRestart match {
        case Failure(_: TimeoutException) => true
        case _ => false
      }
      assert(firstTryFailedToConnect, afterRestart.isSuccess)
    }

    TestUtil.await(5, TimeUnit.SECONDS)(test)
  }

  test("restarting closes client and debuggee") {
    val canceled = Promise[Boolean]()
    val runner: DebuggeeRunner = _ =>
      Task
        .fromFuture(canceled.future)
        .map(_ => ())
        .doOnCancel(Task(canceled.success(true)))

    val server = DebugServer.create(runner, scheduler, logger)

    val test = for {
      uri <- start(server)
      firstClient = connectToDebugAdapter(uri)
      _ <- firstClient.disconnect(restart = true)
      secondClient = connectToDebugAdapter(uri)
      debuggeeCanceled <- Task.fromFuture(canceled.future)
      firstClientClosed <- Task(await(firstClient.socket.isClosed))
      secondClientStillConnected = Try(
        TestUtil.await(1, TimeUnit.SECONDS)(Task(await(secondClient.socket.isClosed)))
      ).isFailure
    } yield {
      assert(debuggeeCanceled, firstClientClosed, secondClientStillConnected)
    }

    TestUtil.await(5, TimeUnit.SECONDS)(test)
  }

  test("disconnecting closes server, client and debuggee") {
    val canceled = Promise[Boolean]()
    val runner: DebuggeeRunner = _ =>
      Task
        .fromFuture(canceled.future)
        .doOnCancel(Task(canceled.success(true)))
        .map(_ => ())

    val server = DebugServer.create(runner, scheduler, logger)

    val test = for {
      uri <- start(server)
      client = connectToDebugAdapter(uri)
      _ <- client.disconnect(restart = false)
      debuggeeCanceled <- Task.fromFuture(canceled.future)
      clientClosed <- Task(await(client.socket.isClosed))
      serverClosed <- Task(await(connectionRefused(uri)))
    } yield {
      assert(debuggeeCanceled, clientClosed, serverClosed)
    }

    TestUtil.await(5, TimeUnit.SECONDS)(test)
  }

  test("close the client even though the debuggee cannot close") {
    val blockedDebuggee = Promise[Nothing]
    val runner: DebuggeeRunner = _ => Task.fromFuture(blockedDebuggee.future)

    val server = DebugServer.create(runner, scheduler, logger)

    val test = for {
      uri <- start(server)
      client = connectToDebugAdapter(uri)
      _ <- stop(server)
      clientDisconnected <- Task(await(client.socket.isClosed))
    } yield {
      assert(clientDisconnected)
    }

    TestUtil.await(10, TimeUnit.SECONDS)(test) // higher limit to accommodate the timout
  }

  @tailrec
  private def await(condition: => Boolean): Boolean = {
    if (condition) true
    else {
      Thread.sleep(250)
      await(condition)
    }
  }

  def connectionRefused(uri: URI): Boolean =
    Try(connectToDebugAdapter(uri)) match {
      case Failure(e: ConnectException) =>
        e.getMessage == "Connection refused (Connection refused)"
      case _ => false
    }

  private def start(server: DebugServer): Task[URI] = {
    val task = server.listen.runOnComplete(_ => servers -= server)(scheduler)
    servers += (server -> task)

    server.address.map {
      case None => throw new IllegalStateException("Server is not listening")
      case Some(uri) => uri
    }
  }

  private def stop(server: DebugServer): Task[Unit] = {
    Task(servers(server).cancel())
  }

  private def connectToDebugAdapter(uri: URI): DebugAdapterConnection = {
    DebugAdapterConnection.connectTo(uri)(scheduler)
  }

  private def runMain(
      project: TestProject,
      state: DebugServerSpec.ManagedBspTestState
  ): DebuggeeRunner = {
    val testState = state.toTestState
    DebuggeeRunner.runMainClass(
      Seq(testState.getProjectFor(project)),
      new ScalaMainClass("Main", Nil, Nil),
      testState.state
    ) match {
      case Right(value) => value
      case Left(error) => throw new Exception(error)
    }
  }
}
