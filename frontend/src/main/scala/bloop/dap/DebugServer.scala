package bloop.dap

import java.net.URI
import java.net.InetSocketAddress

import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}

import ch.epfl.scala.bsp.ScalaMainClass

import bloop.data.Project
import bloop.engine.State
import bloop.exec.JavaEnv
import bloop.data.Platform
import bloop.io.ServerHandle
import bloop.engine.tasks.Tasks
import bloop.engine.tasks.RunMode

import scala.util.Failure
import scala.concurrent.Promise
import scalaz.effect.IoExceptionOr.IoException
import java.net.ServerSocket
import scala.collection.mutable
import monix.execution.Cancelable

sealed trait DebugServer {
  def run(logger: DebugSessionLogger): Task[Unit]
}

final class MainClassDebugServer(
    project: Project,
    mainClass: ScalaMainClass,
    env: JavaEnv,
    state0: State
) extends DebugServer {
  def run(logger: DebugSessionLogger): Task[Unit] = {
    val stateForDebug = state0.copy(logger = logger)
    val workingDir = state0.commonOptions.workingPath
    val runState = Tasks.runJVM(
      stateForDebug,
      project,
      env,
      workingDir,
      mainClass.`class`,
      mainClass.arguments.toArray,
      skipJargs = false,
      RunMode.Debug
    )

    runState.map(_ => ())
  }
}

object MainClassDebugServer {
  def apply(
      projects: Seq[Project],
      mainClass: ScalaMainClass,
      state: State
  ): Either[String, MainClassDebugServer] = {
    val project = projects.head
    project.platform match {
      case jvm: Platform.Jvm => Right(new MainClassDebugServer(project, mainClass, jvm.env, state))
      case platform => Left(s"Unsupported platform: ${platform.getClass.getSimpleName}")
    }
  }
}

object DebugServer {
  def listenTo(
      handle: ServerHandle,
      server: DebugServer,
      ioScheduler: Scheduler,
      startedListening: Promise[Boolean]
  ): Task[Unit] = {
    val servedRequests = mutable.ListBuffer[CancelableFuture[Unit]]()
    def listen(serverSocket: ServerSocket): Task[Unit] = {
      val restart = Promise[Boolean]()
      val listenAndServeClient = Task {
        val debugAddress = Promise[InetSocketAddress]()
        startedListening.trySuccess(true)
        val socket = serverSocket.accept()
        var runningSession: Option[DebugSession] = None
        val dispatchTask = DebugSession.open(socket, debugAddress, restart, ioScheduler).map {
          session =>
            runningSession = Some(session)
            val logger = new DebugSessionLogger(session, debugAddress)
            servedRequests.+=(server.run(logger).runAsync(ioScheduler))
            session.run()

            // If not set by java-debug when handling `Disconnect`, set it to false
            restart.trySuccess(false)
        }

        // TODO: Improve cancellation, think about thrown exceptions
        dispatchTask.doOnCancel(Task(runningSession.foreach(_.stop())))
      }.flatten

      listenAndServeClient.flatMap { _ =>
        Task.fromFuture(restart.future).flatMap { restart =>
          if (restart) listen(serverSocket)
          else Task.eval(serverSocket.close())
        }
      }
    }

    def closeServer(serverSocket: ServerSocket, t: Option[Throwable]): Task[Unit] = {
      Task {
        startedListening.trySuccess(false)
        Cancelable.cancelAll(servedRequests.toList)
        // TODO: Think how to handle exceptions thrown here
        serverSocket.close()
      }
    }

    val startAndListen = Task.eval(handle.fireServer).flatMap { serverSocket =>
      listen(serverSocket)
        .doOnFinish(closeServer(serverSocket, _))
        .doOnCancel(closeServer(serverSocket, None))
    }

    startAndListen.doOnFinish {
      case _ => Task { startedListening.trySuccess(false); () }
    }
  }
}
