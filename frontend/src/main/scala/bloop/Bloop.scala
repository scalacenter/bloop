package bloop

import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Build, Exit, Interpreter, Run, State}
import bloop.engine.tasks.CompileTasks
import bloop.io.AbsolutePath
import bloop.io.Timer.timed
import bloop.logging.Logger

import scala.annotation.tailrec

object Bloop {
  private final val logger = Logger.get
  def main(args: Array[String]): Unit = {
    val baseDirectory = AbsolutePath(args.lift(0).getOrElse(".."))
    val configDirectory = baseDirectory.resolve(".bloop-config")
    val projects = Project.fromDir(configDirectory, logger)
    val build: Build = Build(configDirectory, projects)
    val state = State(build, logger)
    run(state)
  }

  @tailrec
  def run(state: State): Unit = {
    val input = scala.io.StdIn.readLine("> ")
    input.split(" ") match {
      case Array("exit") =>
        timed(logger) { CompileTasks.persist(state) }
        ()

      case Array("projects") =>
        val action = Run(Commands.Projects(), Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case Array("clean") =>
        val allProjects = state.build.projects.map(_.name)
        val action = Run(Commands.Clean(allProjects), Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case Array("compile", projectName) =>
        val action = Run(Commands.Compile(projectName), Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case Array("test", projectName) =>
        val command = Commands.Test(projectName, prependCompile = false, aggregate = true)
        val action = Run(command, Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case _ =>
        logger.error(s"Not understood: '$input'")
        run(state)
    }
  }
}
