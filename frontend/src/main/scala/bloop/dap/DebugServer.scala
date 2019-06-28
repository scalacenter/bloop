package bloop.dap

import java.net.URI
import java.net.InetSocketAddress

import bloop.ConnectionHandle
import monix.eval.Task
import monix.execution.{CancelableFuture, Scheduler}
import scalaz.effect.IoExceptionOr.IoException
import ch.epfl.scala.bsp.ScalaMainClass

import bloop.data.Project
import bloop.data.Platform
import bloop.engine.State
import bloop.engine.tasks.Tasks
import scala.util.Failure
import bloop.exec.JavaEnv
import bloop.engine.tasks.RunMode
import scala.concurrent.Promise

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
  def listenTo(connection: ConnectionHandle, server: DebugServer)(
      ioScheduler: Scheduler
  ): Task[Unit] = {
    val connection = ConnectionHandle.tcp(backlog = 10)
    Task {
      // TODO: Implement cancellation of this
      var open = true
      while (open && !Thread.currentThread().isInterrupted) {
        try {
          val socket = concurrent.blocking(connection.serverSocket.accept())
          val addressPromise = Promise[InetSocketAddress]()
          val session = DebugSession.open(socket, addressPromise)(ioScheduler)
          val handleDebugClient = session.map { session =>
            val logger = new DebugSessionLogger(session, addressPromise)
            val task = server.run(logger).runAsync(ioScheduler) // run the proccess
            session.run()
            task.cancel() // in case we disconnect from running process
          }

          handleDebugClient.runAsync(ioScheduler)
        } catch {
          case _: IoException =>
            connection.close()
            open = false
        }
      }
    }
  }
}
