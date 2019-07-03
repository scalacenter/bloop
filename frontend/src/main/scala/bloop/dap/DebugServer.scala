package bloop.dap

import java.net.{InetSocketAddress, ServerSocket, Socket}

import bloop.data.{Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.exec.JavaEnv
import bloop.io.ServerHandle
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task
import monix.execution.{Cancelable, Scheduler}

import scala.collection.mutable
import scala.concurrent.Promise

trait DebugServer {
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
    val servedRequests = mutable.Set[DebugSession]()
    def listen(serverSocket: ServerSocket): Task[Unit] = {
      val listenAndServeClient = Task {
        startedListening.trySuccess(true)
        val socket = serverSocket.accept()
        DebugSession.open(socket, server.run, ioScheduler).flatMap { session =>
          servedRequests.add(session)

          session.run()

          val awaitExit = session.exitStatus()
          awaitExit
            .doOnFinish(_ => Task.eval(servedRequests.remove(session)))
            .doOnCancel(Task {
              servedRequests.remove(session)
              session.cancel()
            })
        }
      }.flatten

      listenAndServeClient.flatMap {
        case DebugSession.Restarted => listen(serverSocket)
        case DebugSession.Terminated => Task.eval(serverSocket.close())
      }
    }

    def closeServer(serverSocket: ServerSocket, t: Option[Throwable]): Task[Unit] = {
      Task {
        startedListening.trySuccess(false)
        Cancelable.cancelAll(servedRequests)
        // TODO: Think how to handle exceptions thrown here
        serverSocket.close()
      }
    }

    val startAndListen = Task.eval(handle.server).flatMap { serverSocket =>
      listen(serverSocket)
        .doOnFinish(closeServer(serverSocket, _))
        .doOnCancel(closeServer(serverSocket, None))
    }

    startAndListen.doOnFinish(_ => Task { startedListening.trySuccess(false); () })
  }
}
