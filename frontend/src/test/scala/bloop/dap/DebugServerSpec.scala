package bloop.dap
import java.net.{ConnectException, SocketException, SocketTimeoutException}
import java.util.NoSuchElementException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeUnit.{MILLISECONDS, SECONDS}

import bloop.bsp.BspBaseSuite
import bloop.cli.BspProtocol
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task
import monix.execution.Cancelable

import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Promise, TimeoutException}

object DebugServerSpec extends BspBaseSuite {
  override val protocol: BspProtocol.Local.type = BspProtocol.Local
  private val ServerNotListening = new IllegalStateException("Server is not accepting connections")

  test("closes server connection") {
    startDebugServer(Task.now(())) { server =>
      val test = for {
        _ <- Task(server.cancel())
        serverClosed <- awaitClosed(server)
      } yield {
        assert(serverClosed)
      }

      TestUtil.await(5, SECONDS)(test)
    }
  }

  test("closes client connection") {
    startDebugServer(Task.now(())) { server =>
      val test = for {
        client <- server.connect
        _ <- Task(server.cancel())
        clientClosed <- awaitClosed(client)
      } yield {
        assert(clientClosed)
      }

      TestUtil.await(10, SECONDS)(test)
    }
  }

  test("sends exit and terminated events when cancelled") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    synchronized(wait())  // block for all eternity
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = runMain(project, state)

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.connect
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- Task(server.cancel())
            _ <- client.terminated
            _ <- client.exited
            clientClosed <- awaitClosed(client)
          } yield {
            assert(clientClosed)
          }

          TestUtil.await(10, SECONDS)(test)
        }
      }
    }
  }

  test("sends exit and terminated events when cannot run debuggee") {
    TestUtil.withinWorkspace { workspace =>
      // note that there is nothing that can be run (no sources)
      val project = TestProject(workspace, "p", Nil)

      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, List(project), logger) { state =>
        val runner = runMain(project, state)

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.connect
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
          } yield ()

          TestUtil.await(5, SECONDS)(test)
        }
      }
    }
  }

  test("does not accept a connection unless the previous session requests a restart") {
    startDebugServer(Task.now(())) { server =>
      val test = for {
        firstClient <- server.connect
        secondClient <- server.connect
        requestBeforeRestart <- secondClient.initialize().timeout(FiniteDuration(1, SECONDS)).failed
        _ <- firstClient.disconnect(restart = true)
        _ <- secondClient.initialize().timeout(FiniteDuration(100, MILLISECONDS))
      } yield {
        assert(requestBeforeRestart.isInstanceOf[TimeoutException])
      }

      TestUtil.await(5, SECONDS)(test)
    }
  }

  test("restarting closes current client and debuggee") {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .doOnFinish(_ => Task(cancelled.success(false)))
      .doOnCancel(Task(cancelled.success(true)))

    startDebugServer(awaitCancellation) { server =>
      val test = for {
        firstClient <- server.connect
        _ <- firstClient.initialize()
        _ <- firstClient.disconnect(restart = true)
        secondClient <- server.connect
        debuggeeCanceled <- Task.fromFuture(cancelled.future)
        firstClientClosed <- awaitClosed(firstClient)
        secondClientClosed <- awaitClosed(secondClient)
          .timeoutTo(FiniteDuration(1, SECONDS), Task(false))
      } yield {
        // second client should remain unaffected
        assert(debuggeeCanceled, firstClientClosed, !secondClientClosed)
      }

      TestUtil.await(15, TimeUnit.SECONDS)(test)
    }
  }

  test("disconnecting closes server, client and debuggee") {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .doOnFinish(_ => Task(cancelled.success(false)))
      .doOnCancel(Task(cancelled.success(true)))

    startDebugServer(awaitCancellation) { server =>
      val test = for {
        client <- server.connect
        _ <- client.disconnect(restart = false)
        debuggeeCanceled <- Task.fromFuture(cancelled.future)
        clientClosed <- awaitClosed(client)
        serverClosed <- awaitClosed(server)
      } yield {
        assert(debuggeeCanceled, clientClosed, serverClosed)
      }

      TestUtil.await(5, TimeUnit.SECONDS)(test)
    }
  }

  test("closes the client even though the debuggee cannot close") {
    val blockedDebuggee = Promise[Nothing]

    startDebugServer(Task.fromFuture(blockedDebuggee.future)) { server =>
      val test = for {
        client <- server.connect
        _ <- Task(server.cancel())
        clientDisconnected <- awaitClosed(client)
      } yield {
        assert(clientDisconnected)
      }

      TestUtil.await(20, TimeUnit.SECONDS)(test) // higher limit to accommodate the timout
    }
  }

  private def awaitClosed(client: DebugAdapterConnection): Task[Boolean] = {
    Task(await(client.socket.isClosed))
  }

  @tailrec
  private def await(condition: => Boolean): Boolean = {
    if (condition) true
    else if (Thread.interrupted()) false
    else {
      Thread.sleep(250)
      await(condition)
    }
  }

  private def awaitClosed(server: TestServer): Task[Boolean] = {
    server.connect.failed
      .map {
        case _: SocketTimeoutException => true
        case _: ConnectException => true
        case ServerNotListening => true
        case e: SocketException =>
          val message = e.getMessage
          message.endsWith("(Connection refused)") || message.endsWith("(connect failed)")

        case _ =>
          false
      }
      .onErrorRestartIf {
        case e: NoSuchElementException =>
          e.getMessage == "failed"
      }
  }

  private def runMain(
      project: TestProject,
      state: DebugServerSpec.ManagedBspTestState
  ): DebuggeeRunner = {
    val testState = state.toTestState
    DebuggeeRunner.forMainClass(
      Seq(testState.getProjectFor(project)),
      new ScalaMainClass("Main", Nil, Nil),
      testState.state
    ) match {
      case Right(value) => value
      case Left(error) => throw new Exception(error)
    }
  }

  def startDebugServer(task: Task[_])(f: TestServer => Any): Unit = {
    startDebugServer(_ => task.map(_ => ()))(f)
  }

  def startDebugServer(runner: DebuggeeRunner)(f: TestServer => Any): Unit = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val server = DebugServer.start(runner, logger, defaultScheduler)

    val testServer = new TestServer(server)
    val test = Task(f(testServer))
      .doOnFinish(_ => Task(testServer.close()))
      .doOnCancel(Task(testServer.close()))

    TestUtil.await(15, SECONDS)(test)
  }

  override def test(name: String)(fun: => Any): Unit = {
    super.test(name)(fun)
  }

  private final class TestServer(val server: StartedDebugServer)
      extends Cancelable
      with AutoCloseable {
    private val task = server.listen.runAsync(defaultScheduler)
    private val clients = mutable.Set.empty[DebugAdapterConnection]

    override def cancel(): Unit = {
      task.cancel()
    }

    // terminates both server and its clients
    override def close(): Unit = {
      cancel()
      val allClientsClosed = clients.map(c => Task(awaitClosed(c)))
      TestUtil.await(10, SECONDS)(Task.sequence(allClientsClosed))

      clients.foreach { client =>
        client.socket.close()
      }
    }

    def connect: Task[DebugAdapterConnection] = {
      server.address.flatMap {
        case Some(uri) =>
          val connection = DebugAdapterConnection.connectTo(uri)(defaultScheduler)
          clients += connection
          Task(connection)
        case None =>
          throw ServerNotListening
      }
    }
  }
}
