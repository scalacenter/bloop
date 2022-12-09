package bloop.dap

import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.NoSuchElementException
import java.util.concurrent.TimeUnit.MILLISECONDS
import java.util.concurrent.TimeUnit.SECONDS

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.concurrent.duration.Duration
import scala.concurrent.duration.FiniteDuration

import ch.epfl.scala.bsp.ScalaMainClass
import ch.epfl.scala.debugadapter._

import bloop.ScalaInstance
import bloop.bsp.ScalaTestSuiteSelection
import bloop.bsp.ScalaTestSuites
import bloop.cli.ExitStatus
import bloop.data.Platform
import bloop.data.Project
import bloop.engine.State
import bloop.engine.tasks.RunMode
import bloop.engine.tasks.Tasks
import bloop.internal.build.BuildTestInfo
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.logging.Logger
import bloop.logging.LoggerAction
import bloop.logging.LoggerAction.LogInfoMessage
import bloop.logging.NoopLogger
import bloop.logging.ObservedLogger
import bloop.logging.RecordingLogger
import bloop.reporter.ReporterAction
import bloop.task.Task
import bloop.util.TestProject
import bloop.util.TestUtil

import com.microsoft.java.debug.core.protocol.Requests.SetBreakpointArguments
import com.microsoft.java.debug.core.protocol.Types
import com.microsoft.java.debug.core.protocol.Types.SourceBreakpoint
import monix.execution.Ack
import monix.reactive.Observer

object DebugServerSpec extends DebugBspBaseSuite {
  private val ServerNotListening = new IllegalStateException("Server is not accepting connections")
  private val Success: ExitStatus = ExitStatus.Ok
  private val resolver = new BloopDebugToolsResolver(NoopLogger)

  testTask("cancelling server closes server connection", FiniteDuration(10, SECONDS)) {
    startDebugServer(Task.now(Success)) { server =>
      for {
        _ <- Task(server.cancel())
        serverClosed <- waitForServerEnd(server)
      } yield {
        assert(serverClosed)
      }
    }
  }

  testTask("cancelling server closes client connection", FiniteDuration(10, SECONDS)) {
    startDebugServer(Task.now(Success)) { server =>
      for {
        client <- server.startConnection
        _ <- Task(server.cancel())
        _ <- Task.fromFuture(client.closedPromise.future)
      } yield ()
    }
  }

  testTask("sends exit and terminated events when cancelled", FiniteDuration(30, SECONDS)) {
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

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- Task(server.cancel())
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield ()
        }
      }
    }
  }

  testTask(
    "closes the client when debuggee finished and terminal events are sent",
    FiniteDuration(60, SECONDS)
  ) {
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

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          for {
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
                .mkString(lineSeparator),
              "Hello, World!"
            )
          }
        }
      }
    }
  }

  testTask(
    "accepts arguments and jvm options and environment variables",
    FiniteDuration(60, SECONDS)
  ) {
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

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(
          project,
          state,
          arguments = List("hello"),
          jvmOptions = List("-J-Dworld=world"),
          environmentVariables = List("EXCL=!")
        )

        startDebugServer(runner) { server =>
          for {
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
                .mkString(lineSeparator),
              "hello\nworld!"
            )
          }
        }
      }
    }
  }

  testTask("supports scala and java breakpoints", FiniteDuration(60, SECONDS)) {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val javaClass: String =
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
        val scalaMain: String =
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

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        val buildProject = state.toTestState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `Main.scala` = srcFor("Main.scala")
        val `HelloJava.java` = srcFor("HelloJava.java")
        val scalaBreakpoints = breakpointsArgs(`Main.scala`, 3, 13, 20, 14, 9)
        val javaBreakpoints = breakpointsArgs(`HelloJava.java`, 3, 7)

        startDebugServer(runner) { server =>
          for {
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
        }
      }
    }
  }

  testTask(
    "requesting stack traces and variables after breakpoints works",
    FiniteDuration(60, SECONDS)
  ) {
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

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        val buildProject = state.toTestState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `Main.scala` = srcFor("Main.scala")
        val breakpoints = breakpointsArgs(`Main.scala`, 4)

        startDebugServer(runner) { server =>
          for {
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
        }
      }
    }
  }

  testTask(
    "sends exit and terminated events when cannot run debuggee",
    FiniteDuration(60, SECONDS)
  ) {
    TestUtil.withinWorkspace { workspace =>
      // note that there is nothing that can be run (no sources)
      val project = TestProject(workspace, "p", Nil)

      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
          } yield ()
        }
      }
    }
  }

  testTask(
    "does not accept a connection unless the previous session requests a restart",
    FiniteDuration(5, SECONDS)
  ) {
    startDebugServer(Task.now(Success)) { server =>
      for {
        firstClient <- server.startConnection
        secondClient <- server.startConnection
        requestBeforeRestart <- secondClient.initialize().timeout(FiniteDuration(1, SECONDS)).failed
        _ <- firstClient.disconnect(restart = true)
        _ <- secondClient.initialize().timeout(FiniteDuration(100, MILLISECONDS))
      } yield {
        assert(requestBeforeRestart.isInstanceOf[TimeoutException])
      }
    }
  }

  testTask("responds to launch when jvm could not be started", FiniteDuration(20, SECONDS)) {
    // note that the runner is not starting the jvm
    // therefore the debuggee address will never be bound
    val runner = Task.now(Success)

    startDebugServer(runner) { server =>
      for {
        client <- server.startConnection
        _ <- client.initialize()
        cause <- client.launch().failed
      } yield {
        assertContains(cause.getMessage, "Operation timed out")
      }
    }
  }

  testTask("restarting closes current client and debuggee", FiniteDuration(15, SECONDS)) {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .map(_ => Success)
      .doOnFinish(_ => complete(cancelled, false))
      .doOnCancel(complete(cancelled, true))

    startDebugServer(awaitCancellation) { server =>
      for {
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
    }
  }

  testTask("disconnecting closes server, client and debuggee", FiniteDuration(20, SECONDS)) {
    val cancelled = Promise[Boolean]()
    val awaitCancellation = Task
      .fromFuture(cancelled.future)
      .map(_ => Success)
      .doOnFinish(_ => complete(cancelled, false))
      .doOnCancel(complete(cancelled, true))

    startDebugServer(awaitCancellation) { server =>
      for {
        client <- server.startConnection
        _ <- client.disconnect(restart = false)
        debuggeeCanceled <- Task.fromFuture(cancelled.future)
        _ <- Task.fromFuture(client.closedPromise.future)
        serverClosed <- waitForServerEnd(server)
      } yield {
        assert(debuggeeCanceled, serverClosed)
      }
    }
  }

  testTask("closes the client even though the debuggee cannot close", FiniteDuration(20, SECONDS)) {
    val blockedDebuggee = Promise[Nothing]

    startDebugServer(Task.fromFuture(blockedDebuggee.future)) { server =>
      for {
        client <- server.startConnection
        _ <- Task(server.cancel())
        _ <- Task.fromFuture(client.closedPromise.future)
      } yield ()
    }
  }

  testTask("propagates launch failure cause", FiniteDuration(20, SECONDS)) {
    val task = Task.raiseError(new Exception(ExitStatus.RunError.name))

    startDebugServer(task) { server =>
      for {
        client <- server.startConnection
        _ <- client.initialize()
        response <- client.launch().failed
      } yield {
        assert(response.getMessage.contains(ExitStatus.RunError.name))
      }
    }
  }

  testTask("attaches to a remote process and sets breakpoint", FiniteDuration(120, SECONDS)) {
    TestUtil.withinWorkspace { workspace =>
      val main =
        """|/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello, World!")
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "r", List(main))

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val testState = state.compile(project).toTestState
        val buildProject = testState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `Main.scala` = srcFor("Main.scala")
        val breakpoints = breakpointsArgs(`Main.scala`, 3)

        val attachRemoteProcessRunner =
          BloopDebuggeeRunner.forAttachRemote(
            state.compile(project).toTestState.state,
            defaultScheduler,
            Seq(buildProject)
          )

        startDebugServer(attachRemoteProcessRunner) { server =>
          for {
            port <- startRemoteProcess(buildProject, testState)
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.attach("localhost", port)
            breakpoints <- client.setBreakpoints(breakpoints)
            _ = assert(breakpoints.breakpoints.forall(_.verified))
            _ <- client.configurationDone()
            stopped <- client.stopped
            outputOnBreakpoint <- client.takeCurrentOutput
            _ <- client.continue(stopped.threadId)
            _ <- client.exited
            _ <- client.terminated
            finalOutput <- client.takeCurrentOutput
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(client.socket.isClosed)

            assertNoDiff(outputOnBreakpoint, "")

            assertNoDiff(
              finalOutput,
              ""
            )
          }
        }
      }
    }
  }

  testTask("evaluate expression in main debuggee", FiniteDuration(60, SECONDS)) {
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
      val project = TestProject(
        workspace,
        "r",
        List(source),
        scalaVersion = Some("2.12.17")
      )

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        val buildProject = state.toTestState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `Main.scala` = srcFor("Main.scala")
        val breakpoints = breakpointsArgs(`Main.scala`, 4)

        startDebugServer(runner) { server =>
          for {
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
            evaluation <- client.evaluate(topFrame.id, "foo.toString")
            _ <- client.continue(stopped.threadId)
            _ <- client.exited
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(client.socket.isClosed)
            assertNoDiff(evaluation.`type`, "String")
            assertNoDiff(evaluation.result, "\"foo\"")
          }
        }
      }
    }
  }

  testTask("set-workspace-directory", FiniteDuration(60, SECONDS)) {
    TestUtil.withinWorkspace { workspace =>
      val source =
        """|/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    println(java.nio.file.Paths.get("").toAbsolutePath())
           |  }
           |}
           |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(
        workspace,
        "r",
        List(source)
      )

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = mainRunner(project, state)

        startDebugServer(runner) { server =>
          for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            finalOutput <- client.takeCurrentOutput
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(client.socket.isClosed)
            assertNoDiff(finalOutput, workspace.toString)
          }
        }
      }
    }
  }

  testTask("evaluate expression in test suite", FiniteDuration(60, SECONDS)) {
    TestUtil.withinWorkspace { workspace =>
      val source =
        """/MySuite.scala
          |class MySuite {
          |  @org.junit.Test
          |  def myTest(): Unit = {
          |    val foo = new Foo
          |    println(foo)
          |  }
          |}
          |
          |class Foo {
          |  override def toString = "foo"
          |}""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val scalaVersion = "2.12.17"
      val compilerJars = ScalaInstance
        .resolve("org.scala-lang", "scala-compiler", scalaVersion, logger)
        .allJars
        .map(AbsolutePath.apply)
      val junitJars = BuildTestInfo.junitTestJars.map(AbsolutePath.apply)

      val project = TestProject(
        workspace,
        "r",
        List(source),
        enableTests = true,
        jars = compilerJars ++ junitJars,
        scalaVersion = Some(scalaVersion)
      )

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val runner = testRunner(project, state)

        val buildProject = state.toTestState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `MySuite.scala` = srcFor("MySuite.scala")
        val breakpoints = breakpointsArgs(`MySuite.scala`, 5)

        startDebugServer(runner) { server =>
          for {
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
            evaluation <- client.evaluate(topFrame.id, "foo.toString")
            _ <- client.continue(stopped.threadId)
            _ <- client.exited
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(client.socket.isClosed)
            assertNoDiff(evaluation.`type`, "String")
            assertNoDiff(evaluation.result, "\"foo\"")
          }
        }
      }
    }
  }

  testTask("evaluate expression in attached debuggee", FiniteDuration(120, SECONDS)) {
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
      val project = TestProject(
        workspace,
        "r",
        List(source),
        scalaVersion = Some("2.12.17")
      )

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val testState = state.compile(project).toTestState
        val buildProject = testState.getProjectFor(project)
        def srcFor(srcName: String) =
          buildProject.sources.map(_.resolve(srcName)).find(_.exists).get
        val `Main.scala` = srcFor("Main.scala")
        val breakpoints = breakpointsArgs(`Main.scala`, 4)

        val attachRemoteProcessRunner =
          BloopDebuggeeRunner.forAttachRemote(
            testState.state,
            defaultScheduler,
            Seq(buildProject)
          )

        startDebugServer(attachRemoteProcessRunner) { server =>
          for {
            port <- startRemoteProcess(buildProject, testState)
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.attach("localhost", port)
            breakpoints <- client.setBreakpoints(breakpoints)
            _ = assert(breakpoints.breakpoints.forall(_.verified))
            _ <- client.configurationDone()
            stopped <- client.stopped
            stackTrace <- client.stackTrace(stopped.threadId)
            topFrame <- stackTrace.stackFrames.headOption
              .map(Task.now)
              .getOrElse(Task.raiseError(new NoSuchElementException("no frames on the stack")))
            evaluation <- client.evaluate(topFrame.id, "foo.toString")
            _ <- client.continue(stopped.threadId)
            _ <- client.exited
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(client.socket.isClosed)
            assertNoDiff(evaluation.`type`, "String")
            assertNoDiff(evaluation.result, "\"foo\"")
          }
        }
      }
    }
  }

  testTask("run only single test", FiniteDuration(60, SECONDS)) {
    TestUtil.withinWorkspace { workspace =>
      val source =
        """/MySuite.scala
          |class MySuite {
          |  @org.junit.Test
          |  def test1(): Unit = {
          |    println("test1")
          |  }
          |  @org.junit.Test
          |  def test2(): Unit = {
          |    println("test2")
          |  }
          |}
          |
          |""".stripMargin

      val logger = new RecordingLogger(ansiCodesSupported = false)

      val scalaVersion = "2.12.17"
      val compilerJars = ScalaInstance
        .resolve("org.scala-lang", "scala-compiler", scalaVersion, logger)
        .allJars
        .map(AbsolutePath.apply)
      val junitJars = BuildTestInfo.junitTestJars.map(AbsolutePath.apply)

      val project = TestProject(
        workspace,
        "r",
        List(source),
        enableTests = true,
        jars = compilerJars ++ junitJars,
        scalaVersion = Some(scalaVersion)
      )

      loadBspStateWithTask(workspace, List(project), logger) { state =>
        val testClasses =
          ScalaTestSuites(
            List(
              ScalaTestSuiteSelection(
                "MySuite",
                List("test1")
              )
            ),
            List("-Xmx512M"),
            List("ENV_KEY=ENV_VALUE")
          )
        val runner = testRunner(project, state, testClasses)

        startDebugServer(runner) { server =>
          for {
            client <- server.startConnection
            _ <- client.initialize()
            _ <- client.launch()
            _ <- client.initialized
            _ <- client.configurationDone()
            _ <- client.exited
            _ <- client.terminated
            _ <- Task.fromFuture(client.closedPromise.future)
          } yield {
            assert(logger.debugs.contains("Running ForkMain with jvm opts: List(-Xmx512M)"))
            assert(
              logger.debugs.contains("Running ForkMain with env variables: List(ENV_KEY=ENV_VALUE)")
            )
            assert(logger.debugs.contains("Test MySuite.test1 started"))
            assert(logger.debugs.contains("Test MySuite.test2 ignored"))
          }
        }
      }
    }
  }

  private def startRemoteProcess(buildProject: Project, testState: TestState): Task[Int] = {
    val attachPort = Promise[Int]()

    val jdkConfig = buildProject.platform match {
      case jvm: Platform.Jvm => jvm.config
      case platform => throw new Exception(s"Unsupported platform $platform")
    }

    val debuggeeLogger = portListeningLogger(
      testState.state.logger,
      port => {
        if (!attachPort.isCompleted) {
          attachPort.success(port)
          ()
        }
      }
    )

    val remoteProcess: Task[State] = Tasks.runJVM(
      testState.state.copy(logger = debuggeeLogger),
      buildProject,
      jdkConfig,
      testState.state.commonOptions.workingPath,
      "Main",
      Array.empty,
      skipJargs = false,
      envVars = List.empty,
      RunMode.Debug
    )

    remoteProcess.runAsync(defaultScheduler)

    Task.fromFuture(attachPort.future)
  }

  private def portListeningLogger(underlying: Logger, listener: Int => Unit): Logger = {
    val listeningObserver = new Observer[Either[ReporterAction, LoggerAction]] {
      override def onNext(elem: Either[ReporterAction, LoggerAction]): Future[Ack] = elem match {
        case Right(action) =>
          action match {
            case LogInfoMessage(msg) =>
              println(msg)
              if (msg.startsWith(DebuggeeLogger.JDINotificationPrefix)) {
                val port =
                  Integer.parseInt(msg.drop(DebuggeeLogger.JDINotificationPrefix.length))
                listener(port)
              }
              Ack.Continue
            case _ => Ack.Continue
          }
        case _ => Ack.Continue
      }
      override def onError(ex: Throwable): Unit = throw ex
      override def onComplete(): Unit = ()
    }

    ObservedLogger(underlying, listeningObserver)
  }

  private def breakpointsArgs(source: AbsolutePath, lines: Int*): SetBreakpointArguments = {
    val arguments = new SetBreakpointArguments()
    val breakpoints = lines.map { line =>
      val breakpoint = new SourceBreakpoint()
      breakpoint.line = line
      breakpoint
    }
    arguments.source = new Types.Source(source.syntax, 0)
    arguments.sourceModified = false
    arguments.breakpoints = breakpoints.toArray
    arguments
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

  private def testRunner(
      project: TestProject,
      state: ManagedBspTestState,
      testClasses: ScalaTestSuites = ScalaTestSuites(List("MySuite"))
  ): Debuggee = {
    val testState = state.compile(project).toTestState
    BloopDebuggeeRunner.forTestSuite(
      Seq(testState.getProjectFor(project)),
      testClasses,
      testState.state,
      defaultScheduler
    ) match {
      case Right(value) => value
      case Left(error) => throw new Exception(error)
    }
  }

  private def mainRunner(
      project: TestProject,
      state: ManagedBspTestState,
      arguments: List[String] = Nil,
      jvmOptions: List[String] = Nil,
      environmentVariables: List[String] = Nil
  ): Debuggee = {
    val testState = state.compile(project).toTestState
    BloopDebuggeeRunner.forMainClass(
      Seq(testState.getProjectFor(project)),
      new ScalaMainClass("Main", arguments, jvmOptions, environmentVariables),
      testState.state,
      defaultScheduler
    ) match {
      case Right(value) => value
      case Left(error) => throw new Exception(error)
    }
  }

  def startDebugServer(task: Task[ExitStatus])(f: TestServer => Task[Unit]): Task[Unit] = {
    val debuggee = new Debuggee {
      override def modules: Seq[Module] = Seq.empty
      override def libraries: Seq[Library] = Seq.empty
      override def unmanagedEntries: Seq[UnmanagedEntry] = Seq.empty
      override def javaRuntime: Option[JavaRuntime] = None
      def name: String = "MockRunner"
      def run(listener: DebuggeeListener): CancelableFuture[Unit] = {
        DapCancellableFuture.runAsync(task.map(_ => ()), defaultScheduler)
      }
      def scalaVersion: ScalaVersion = ScalaVersion("2.12.17")
    }

    startDebugServer(
      debuggee,
      // the runner is a mock so no need to wait for the jvm to startup
      // make the test faster
      Duration.Zero
    )(f)
  }

  def startDebugServer(
      debuggee: Debuggee,
      gracePeriod: Duration = Duration(5, SECONDS)
  )(f: TestServer => Task[Unit]): Task[Unit] = {
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val dapLogger = new DebugServerLogger(logger)
    val debugTools = DebugTools(debuggee, resolver, dapLogger)

    val config = DebugConfig.default.copy(gracePeriod = gracePeriod)
    val server = DebugServer(debuggee, debugTools, dapLogger, config = config)(defaultScheduler)
    Task.fromFuture(server.start()).runAsync(defaultScheduler)

    val testServer = new TestServer(server)
    f(testServer)
      .doOnFinish(_ => Task(testServer.close()))
      .doOnCancel(Task(testServer.close()))

  }

  private final class TestServer(val server: DebugServer) extends AutoCloseable {
    private val clients = mutable.Set.empty[DebugAdapterConnection]

    def cancel(): Unit = {
      server.close()
    }

    // terminates both server and its clients
    override def close(): Unit = {
      cancel()
      val allClientsClosed = clients.map(c => Task.fromFuture(c.closedPromise.future))
      TestUtil.await(10, SECONDS)(Task.sequence(allClientsClosed)); ()
    }

    def startConnection: Task[DebugAdapterConnection] = Task {
      val connection = DebugAdapterConnection.connectTo(server.uri)(defaultScheduler)
      clients += connection
      connection
    }
  }

  private def complete[A](promise: Promise[A], value: A): Task[Unit] =
    Task {
      promise.trySuccess(value)
      ()
    }
}
