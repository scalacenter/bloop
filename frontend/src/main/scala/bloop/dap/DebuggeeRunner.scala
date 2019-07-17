package bloop.dap

import bloop.data.{Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.exec.JavaEnv
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task

trait DebuggeeRunner {
  def run(logger: DebugSessionLogger): Task[Unit]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    env: JavaEnv,
    state: State
) extends DebuggeeRunner {
  def run(logger: DebugSessionLogger): Task[Unit] = {
    val stateForDebug = state.copy(logger = logger)
    val workingDir = state.commonOptions.workingPath
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

object DebuggeeRunner {
  def forMainClass(
      projects: Seq[Project],
      mainClass: ScalaMainClass,
      state: State
  ): Either[String, DebuggeeRunner] = {
    projects match {
      case Seq() =>
        Left(s"No projects specified for the main class: [$mainClass]")
      case project :: Seq() =>
        project.platform match {
          case jvm: Platform.Jvm =>
            Right(new MainClassDebugAdapter(project, mainClass, jvm.env, state))
          case platform =>
            Left(s"Unsupported platform: ${platform.getClass.getSimpleName}")
        }
      case _ =>
        Left(s"Multiple projects specified for the main class [$mainClass]")
    }
  }
}
