package bloop.dap

import bloop.data.{Platform, Project}
import bloop.engine.State
import bloop.engine.tasks.{RunMode, Tasks}
import bloop.exec.JavaEnv
import ch.epfl.scala.bsp.ScalaMainClass
import monix.eval.Task

trait DebugAdapter {
  def run(logger: DebugSessionLogger): Task[Unit]
}

private final class MainClassDebugAdapter(
    project: Project,
    mainClass: ScalaMainClass,
    env: JavaEnv,
    state0: State
) extends DebugAdapter {
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

object DebugAdapter {
  def runMainClass(
      projects: Seq[Project],
      mainClass: ScalaMainClass,
      state: State
  ): Either[String, DebugAdapter] = {
    val project = projects.head
    project.platform match {
      case jvm: Platform.Jvm =>
        Right(new MainClassDebugAdapter(project, mainClass, jvm.env, state))
      case platform => Left(s"Unsupported platform: ${platform.getClass.getSimpleName}")
    }
  }
}
