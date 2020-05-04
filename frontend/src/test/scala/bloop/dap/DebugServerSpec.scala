package bloop.dap

import java.net.{ConnectException, SocketException, SocketTimeoutException}
import java.util.NoSuchElementException
import java.util.concurrent.TimeUnit.{MILLISECONDS, SECONDS}
import bloop.cli.ExitStatus
import bloop.logging.RecordingLogger
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task
import monix.execution.Cancelable
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Promise, TimeoutException}
import bloop.engine.ExecutionContext

import com.microsoft.java.debug.core.protocol.Requests.SetBreakpointArguments
import com.microsoft.java.debug.core.protocol.Types
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint
import java.nio.file.Path
import bloop.logging.Logger
import bloop.logging.NoopLogger

object DebugServerSpec extends DebugBspBaseSuite {
  private val ServerNotListening = new IllegalStateException("Server is not accepting connections")
  private val Success: ExitStatus = ExitStatus.Ok

  test("cancelling server closes server connection") {
    startDebugServer(Task.now(Success)) { server =>
      val test = for {
        _ <- Task(server.cancel())
        serverClosed <- waitForServerEnd(server)
      } yield {
        assert(serverClosed)
      }

      TestUtil.await(FiniteDuration(10, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  test("cancelling server closes client connection") {
    startDebugServer(Task.now(Success)) { server =>
      val test = for {
        client <- server.startConnection
        _ <- Task(server.cancel())
        _ <- Task.fromFuture(client.closedPromise.future)
      } yield ()

      TestUtil.await(FiniteDuration(10, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  test("sends exit and terminated events when cancelled") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    synchronized(wait())  // block for all eternity
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- Task(server.cancel())
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield ()

          TestUtil.await(FiniteDuration(30, SECONDS), ExecutionContext.ioScheduler)(test)
        }
      }
    }
  }

  test("closes the client when debuggee finished and terminal events are sent") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello, World!")
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "r", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
            output <- client.takeCurrentOutput
          } yield {
            assert(client.socket.isClosed)
            assertNoDiff(
              output.linesIterator
                .filterNot(_.contains("ERROR: JDWP Unable to get JNI 1.2 environment"))
                .filterNot(_.contains("JDWP exit error AGENT_ERROR_NO_JNI_ENV"))
                .mkString(System.lineSeparator),
              "Hello, World!"
            )
          }

          TestUtil.await(FiniteDuration(60, SECONDS), ExecutionContext.ioScheduler)(test)
        }
      }
    }
  }

  test("accepts arguments and jvm options and environment variables") {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println(args(0))
           |    print(sys.props("world"))
           |    print(sys.env("EXCL"))
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "r", List(main))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(
          project,
          state,
          arguments = List("hello"),
          jvmOptions = List("-J-Dworld=world"),
          environmentVariables = List("EXCL=!")
        )

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
            output <- client.takeCurrentOutput
          } yield {
            assert(client.socket.isClosed)
            assertNoDiff(
              output.linesIterator
                .filterNot(_.contains("ERROR: JDWP Unable to get JNI 1.2 environment"))
                .filterNot(_.contains("JDWP exit error AGENT_ERROR_NO_JNI_ENV"))
                .mkString(System.lineSeparator),
              "hello\nworld!"
            )
          }

          TestUtil.await(FiniteDuration(60, SECONDS), ExecutionContext.ioScheduler)(test)
        }
      }
    }
  }

  test("supports scala and java breakpoints") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val javaClass =
          """|/HelloJava.java
             |public class HelloJava {
             |  public HelloJava() {
             |    System.out.println("Breakpoint in hello java class constructor");
             |  }
             |  
             |  public void greet() {
             |    System.out.println("Breakpoint in hello java greet method");
             |  }
             |}
             |""".stripMargin
        val scalaMain =
          """|/Main.scala
             |object Main {
             |  def main(args: Array[String]): Unit = {
             |    println("Breakpoint in main method")
             |    val h = new Hello
             |    h.greet()
             |    Hello.a // Force initialization of constructor
             |    val h2 = new HelloJava()
             |    h2.greet()
             |    println("Finished all breakpoints")
             |  }
             |  class Hello {
             |    def greet(): Unit = {
             |      println("Breakpoint in hello class")
             |      class InnerHello { println("Breakpoint in hello inner class") }
             |      new InnerHello()
             |      ()
             |    }
             |  }
             |  object Hello {
             |    println("Breakpoint in hello object")
             |    val a = 1
             |  }
             |}
             |""".stripMargin
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "r", List(Sources.scalaMain, Sources.javaClass))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        val buildProject = state.toTestState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `Main.scala` = srcFor("Main.scala")
        val `HelloJava.java` = srcFor("HelloJava.java")

        val scalaBreakpoints = {
          val arguments = new SetBreakpointArguments()
          // Breakpoint in main method
          val breakpoint1 = new SourceBreakpoint()
          breakpoint1.line = 3
          // Breakpoint in Hello class, in method greet
          val breakpoint2 = new SourceBreakpoint()
          breakpoint2.line = 13
          // Breakpoint in Hello object, inside constructor
          val breakpoint3 = new SourceBreakpoint()
          breakpoint3.line = 20
          // Breakpoint in Hello inner classs
          val breakpoint4 = new SourceBreakpoint()
          breakpoint4.line = 14
          // Breakpoint in end of main method
          val breakpoint5 = new SourceBreakpoint()
          breakpoint5.line = 9
          arguments.source = new Types.Source(`Main.scala`.syntax, 0)
          arguments.sourceModified = false
          arguments.breakpoints =
            Array(breakpoint1, breakpoint2, breakpoint3, breakpoint4, breakpoint5)
          arguments
        }

        val javaBreakpoints = {
          val arguments = new SetBreakpointArguments()
          // Breakpoint in Hello java class, in constructor
          val breakpoint1 = new SourceBreakpoint()
          breakpoint1.line = 3
          // Breakpoint in Hello java class, in method greet
          val breakpoint2 = new SourceBreakpoint()
          breakpoint2.line = 7
          arguments.source = new Types.Source(`HelloJava.java`.syntax, 0)
          arguments.sourceModified = false
          arguments.breakpoints = Array(breakpoint1, breakpoint2)
          arguments
        }

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            scalaBreakpointsResult <- client.setBreakpoints(scalaBreakpoints)
            _ = assert(scalaBreakpointsResult.breakpoints.forall(_.verified))
            javaBreakpointsResult <- client.setBreakpoints(javaBreakpoints)
            _ = assert(javaBreakpointsResult.breakpoints.forall(_.verified))
            _ <- client.configurationDone()
            stopped <- client.stopped
            _ <- client.continue(stopped.threadId)
            stopped2 <- client.stopped
            _ <- client.continue(stopped2.threadId)
            stopped3 <- client.stopped
            _ <- client.continue(stopped3.threadId)
            stopped4 <- client.stopped
            _ <- client.continue(stopped4.threadId)
            stopped5 <- client.stopped
            _ <- client.continue(stopped5.threadId)
            stopped6 <- client.stopped
            _ <- client.continue(stopped6.threadId)
            stopped7 <- client.stopped
            _ <- client.continue(stopped7.threadId)
            _ <- client.exited
            _ <- client.terminated
            finalOutput <- client.takeCurrentOutput
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(client.socket.isClosed)
            assertNoDiff(
              finalOutput,
              """|Breakpoint in main method
                 |Breakpoint in hello class
                 |Breakpoint in hello inner class
                 |Breakpoint in hello object
                 |Breakpoint in hello java class constructor
                 |Breakpoint in hello java greet method
                 |Finished all breakpoints
                 |""".stripMargin
            )
          }

          TestUtil.await(FiniteDuration(60, SECONDS), ExecutionContext.ioScheduler)(test)
        }
      }
    }
  }

  test("requesting stack traces and variables after breakpoints works") {
    TestUtil.withinWorkspace { workspace =>
      val source = """|/Main.scala
                      |object Main {
                      |  def main(args: Array[String]): Unit = {
                      |    val foo = new Foo
                      |    println(foo)
                      |  }
                      |}
                      |
                      |class Foo {
                      |  override def toString = "foo"
                      |}
                      |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "r", List(source))

      loadBspState(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        val buildProject = state.toTestState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `Main.scala` = srcFor("Main.scala")

        val breakpoints = {
          val arguments = new SetBreakpointArguments()
          val breakpoint1 = new SourceBreakpoint()
          breakpoint1.line = 4
          arguments.source = new Types.Source(`Main.scala`.syntax, 0)
          arguments.sourceModified = false
          arguments.breakpoints = Array(breakpoint1)
          arguments
        }

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            breakpoints <- client.setBreakpoints(breakpoints)
            _ = assert(breakpoints.breakpoints.forall(_.verified))
            _ <- client.configurationDone()
            stopped <- client.stopped
            stackTrace <- client.stackTrace(stopped.threadId)
            topFrame <- stackTrace.stackFrames.headOption
              .map(Task.now)
              .getOrElse(Task.raiseError(new NoSuchElementException("no frames on the stack")))
            scopes <- client.scopes(topFrame.id)
            localScope <- scopes.scopes
              .find(_.name == "Local")
              .map(Task.now)
              .getOrElse(Task.raiseError(new NoSuchElementException("no local scope")))
            localVars <- client.variables(localScope.variablesReference)
            _ <- client.continue(stopped.threadId)
            _ <- client.exited
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(client.socket.isClosed)
            val localVariables = localVars.variables
              .map(v => s"${v.name}: ${v.`type`} = ${v.value.takeWhile(c => c != '@')}")

            assertNoDiff(
              localVariables.mkString("\n"),
              """|args: String[] = String[0]
                 |foo: Foo = Foo
                 |this: Main$ = Main$
                 |""".stripMargin
            )
          }

          TestUtil.await(FiniteDuration(60, SECONDS), ExecutionContext.ioScheduler)(test)
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
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          val test = for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
          } yield ()

          TestUtil.await(FiniteDuration(60, SECONDS), ExecutionContext.ioScheduler)(test)
        }
      }
    }
  }

  test("does not accept a connection unless the previous session requests a restart") {
    startDebugServer(Task.now(Success)) { server =>
      val test = for {
        firstClient <- server.startConnection
        secondClient <- server.startConnection
        requestBeforeRestart <- secondClient.initialize().timeout(FiniteDuration(1, SECONDS)).failed
        _ <- firstClient.disconnect(restart = true)
        _ <- secondClient.initialize().timeout(FiniteDuration(100, MILLISECONDS))
      } yield {
        assert(requestBeforeRestart.isInstanceOf[TimeoutException])
      }

      TestUtil.await(FiniteDuration(5, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  test("responds to launch when jvm could not be started") {
    // note that the runner is not starting the jvm
    // therefore the debuggee address will never be bound
    val runner = Task.now(Success)

    startDebugServer(runner) { server =>
      val test = for {
        client <- server.startConnection
        _ <- client.initialize()
        cause <- client.launch().failed
      } yield {
        assertContains(cause.getMessage, "Task timed-out")
      }

      TestUtil.await(FiniteDuration(20, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  test("restarting closes current client and debuggee") {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .map(_ => Success)
      .doOnFinish(_ => complete(cancelled, false))
      .doOnCancel(complete(cancelled, true))

    startDebugServer(awaitCancellation) { server =>
      val test = for {
        firstClient <- server.startConnection
        _ <- firstClient.disconnect(restart = true)
        secondClient <- server.startConnection
        debuggeeCanceled <- Task.fromFuture(cancelled.future)
        _ <- Task.fromFuture(firstClient.closedPromise.future)
        secondClientClosed <- Task
          .fromFuture(secondClient.closedPromise.future)
          .map(_ => true)
          .timeoutTo(FiniteDuration(1, SECONDS), Task(false))
      } yield {
        // Second client should still be connected despite the first one was closed
        assert(debuggeeCanceled, !secondClientClosed)
      }

      TestUtil.await(FiniteDuration(15, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  test("disconnecting closes server, client and debuggee") {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .map(_ => Success)
      .doOnFinish(_ => complete(cancelled, false))
      .doOnCancel(complete(cancelled, true))

    startDebugServer(awaitCancellation) { server =>
      val test = for {
        client <- server.startConnection
        _ <- client.disconnect(restart = false)
        debuggeeCanceled <- Task.fromFuture(cancelled.future)
        _ <- Task.fromFuture(client.closedPromise.future)
        serverClosed <- waitForServerEnd(server)
      } yield {
        assert(debuggeeCanceled, serverClosed)
      }

      TestUtil.await(FiniteDuration(5, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  test("closes the client even though the debuggee cannot close") {
    val blockedDebuggee = Promise[Nothing]

    startDebugServer(Task.fromFuture(blockedDebuggee.future)) { server =>
      val test = for {
        client <- server.startConnection
        _ <- Task(server.cancel())
        _ <- Task.fromFuture(client.closedPromise.future)
      } yield ()

      TestUtil.await(FiniteDuration(20, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  test("propagates launch failure cause") {
    val task = Task.now(ExitStatus.RunError)

    startDebugServer(task) { server =>
      val test = for {
        client <- server.startConnection
        _ <- client.initialize()
        response <- client.launch().failed
      } yield {
        assert(response.getMessage.contains(ExitStatus.RunError.name))
      }

      TestUtil.await(FiniteDuration(20, SECONDS), ExecutionContext.ioScheduler)(test)
    }
  }

  private def waitForServerEnd(server: TestServer): Task[Boolean] = {
    server.startConnection.failed
      .map {
        case _: SocketTimeoutException => true
        case _: ConnectException => true
        case ServerNotListening => true
        case e: SocketException =>
          val message = e.getMessage
          message.endsWith("(Connection refused)") || message.endsWith("(connect failed)")

        case _ => false
      }
      .onErrorRestartIf {
        case e: NoSuchElementException =>
          e.getMessage == "failed"
      }
  }

  private def mainRunner(
      project: TestProject,
      state: DebugServerSpec.ManagedBspTestState,
      arguments: List[String] = Nil,
      jvmOptions: List[String] = Nil,
      environmentVariables: List[String] = Nil
  ): DebuggeeRunner = {
    val testState = state.compile(project).toTestState
    DebuggeeRunner.forMainClass(
      Seq(testState.getProjectFor(project)),
      new ScalaMainClass("Main", arguments, jvmOptions, environmentVariables),
      testState.state
    ) match {
      case Right(value) => value
      case Left(error) => throw new Exception(error)
    }
  }

  def startDebugServer(task: Task[ExitStatus])(f: TestServer => Any): Unit = {
    val runner = new DebuggeeRunner {
      def logger: Logger = NoopLogger
      def run(logger: DebugSessionLogger): Task[ExitStatus] = task
      def classFilesMappedTo(
          origin: Path,
          lines: Array[Int],
          columns: Array[Int]
      ): List[Path] = Nil
    }

    startDebugServer(runner)(f)
  }

  def startDebugServer(runner: DebuggeeRunner)(f: TestServer => Any): Unit = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val server = DebugServer.start(runner, logger, defaultScheduler)

    val testServer = new TestServer(server)
    val test = Task(f(testServer))
      .doOnFinish(_ => Task(testServer.close()))
      .doOnCancel(Task(testServer.close()))

    TestUtil.await(35, SECONDS)(test)
    ()
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
      val allClientsClosed = clients.map(c => Task.fromFuture(c.closedPromise.future))
      TestUtil.await(10, SECONDS)(Task.sequence(allClientsClosed)); ()
    }

    def startConnection: Task[DebugAdapterConnection] = {
      server.address.flatMap {
        case Some(uri) =>
          val connection =
            DebugAdapterConnection.connectTo(uri)(defaultScheduler)
          clients += connection
          Task(connection)
        case None =>
          throw ServerNotListening
      }
    }
  }

  private def complete[A](promise: Promise[A], value: A): Task[Unit] =
    Task {
      promise.trySuccess(value)
      ()
    }
}
