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

// TODO do we need this? task is not cancelled anywhere
sealed trait DebugServer {
  def run(logger: DebugSessionLogger): Task[Unit]
}

final class MainClassDebugServer(
    project: Project,
    mainClass: ScalaMainClass,
    env: JavaEnv,
    state: State
) extends DebugServer {
  def run(logger: DebugSessionLogger): Task[Unit] = {
    val workingDir = state.commonOptions.workingPath
    val runState = Tasks.runJVM(
      state,
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
      project: Project,
      mainClass: ScalaMainClass,
      state: State
  ): Either[String, MainClassDebugServer] = {
    project.platform match {
      case jvm: Platform.Jvm => Right(new MainClassDebugServer(project, mainClass, jvm.env, state))
      case platform => Left(s"Unsupported platform: ${platform.getClass.getSimpleName}")
    }
  }
}

object DebugServer {
  def listenTo(handle: ServerHandle, server: DebugServer, ioScheduler: Scheduler): Task[Unit] = {
    val retryListen = Promise[InetSocketAddress]()
    Task {
      // TODO: Implement cancellation of this
      var open: Boolean = true
      val serverSocket = handle.fireServer
      while (open && !Thread.currentThread().isInterrupted) {
        try {
          val futureDebugAddress = Promise[InetSocketAddress]()
          val socket = concurrent.blocking(serverSocket.accept())
          val session = DebugSession.open(socket, futureDebugAddress, ioScheduler)
          val handleDebugClient = session.map { session =>
            val logger = new DebugSessionLogger(session, futureDebugAddress)
            val task = server.run(logger).runAsync(ioScheduler) // run the proccess
            session.run()
            task.cancel() // in case we disconnect from running process
          }

          handleDebugClient.runAsync(ioScheduler)
        } catch {
          case _: IoException =>
            serverSocket.close()
            open = false
        }
      }
    }
  }
}
