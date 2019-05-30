package bloop.dap

import bloop.data.{Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.Tasks
import bloop.exec.JavaEnv
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.{Failure, Success, Try}

final class MainClassDebuggeeProvider(mainClass: ScalaMainClass) {
  def create(state: State, projects: Seq[Project])(
      implicit scheduler: Scheduler
  ): () => Debuggee = { () =>
    new Debuggee(task(state, projects.head)(_))
  }

  private def task(state: State, project: Project)(session: DebugSession): Task[Unit] = {
    Task.fromTry(environment(project)).flatMap { env =>
      val workingDir = state.commonOptions.workingPath
      val task = Tasks.runJVM(
        state,
        project,
        env,
        workingDir,
        mainClass.`class`,
        mainClass.arguments.toArray,
        skipJargs = false,
        Some(session)
      )

      task.map(_ => ())
    }
  }

  private def environment(project: Project): Try[JavaEnv] = {
    project.platform match {
      case p: Platform.Jvm =>
        Success(p.env)
      case platform =>
        val error = s"Unsupported platform: ${platform.getClass.getSimpleName}"
        Failure(new IllegalStateException(error))
    }
  }
}
