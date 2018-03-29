package bloop

import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.cli.CliParsers.{OptionsParser, inputStreamRead, pathParser, printStreamRead}
import bloop.engine.{Build, Exit, Interpreter, NoPool, Run, State}
import bloop.engine.tasks.Tasks
import bloop.io.AbsolutePath
import bloop.io.Timer.timed
import bloop.logging.BloopLogger
import jline.console.ConsoleReader
import caseapp.{CaseApp, RemainingArgs}
import jline.console.ConsoleReader

import scala.annotation.tailrec

object Bloop extends CaseApp[CliOptions] {
  private val reader = consoleReader()

  override def run(options: CliOptions, remainingArgs: RemainingArgs): Unit = {
    val configDirectory = options.configDir.map(AbsolutePath.apply).getOrElse(AbsolutePath(".bloop"))
    val logger = BloopLogger.default(configDirectory.syntax)
    logger.warn("The Nailgun integration should be preferred over the Bloop shell.")
    logger.warn(
      "The Bloop shell provides less features, is not supported and can be removed without notice.")
    logger.warn("Please refer to our documentation for more information.")
    logger.verboseIf(options.verbose) {
      val projects = Project.fromDir(configDirectory, logger)
      val build = Build(configDirectory, projects)
      val state = State(build, NoPool, options.common, logger)
      run(state)
    }
  }

  @tailrec
  def run(state: State): Unit = {
    State.stateCache.updateBuild(state)
    val input = reader.readLine()
    input.split(" ") match {
      case Array("exit") =>
        timed(state.logger) { Tasks.persist(state) }
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

      case Array("console", projectName) =>
        val action = Run(Commands.Console(projectName), Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case Array("test", projectName) =>
        val command = Commands.Test(projectName)
        val action = Run(command, Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case Array("runMain", projectName, mainClass, args @ _*) =>
        val command = Commands.Run(projectName, Some(mainClass), args = args.toList)
        val action = Run(command, Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case Array("run", projectName, args @ _*) =>
        val command = Commands.Run(projectName, None, args = args.toList)
        val action = Run(command, Exit(ExitStatus.Ok))
        run(Interpreter.execute(action, state))

      case _ =>
        state.logger.error(s"Not understood: '$input'")
        run(state)
    }
  }

  private def consoleReader(): ConsoleReader = {
    val reader = new ConsoleReader()
    reader.setPrompt("> ")
    reader
  }

}
