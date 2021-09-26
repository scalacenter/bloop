package bloop

import bloop.data.ClientInfo
import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.cli.CliParsers.{inputStreamRead, pathParser, printStreamRead, propertiesParser}
import bloop.engine.{Action, Build, BuildLoader, Exit, Interpreter, NoPool, Print, Run, State}
import bloop.engine.tasks.Tasks
import bloop.io.AbsolutePath
import bloop.logging.BloopLogger
import caseapp.{CaseApp, RemainingArgs}
import jline.console.ConsoleReader
import _root_.monix.eval.Task
import bloop.data.WorkspaceSettings
import scala.annotation.tailrec
import scala.concurrent.Promise

object Bloop extends CaseApp[CliOptions] {
  private val reader = consoleReader()

  override def run(options: CliOptions, remainingArgs: RemainingArgs): Unit = {
    val configDirectory =
      options.configDir.map(AbsolutePath.apply).getOrElse(AbsolutePath(".bloop"))
    val logger = BloopLogger.default(configDirectory.syntax)
    logger.warn("The Nailgun integration should be preferred over the Bloop shell.")
    logger.warn(
      "The Bloop shell provides less features, is not supported and can be removed without notice."
    )
    logger.warn("Please refer to our documentation for more information.")
    val client = ClientInfo.CliClientInfo(useStableCliDirs = true, () => true)
    val loadedProjects = BuildLoader.loadSynchronously(configDirectory, logger)
    val workspaceSettings = WorkspaceSettings.readFromFile(configDirectory, logger)
    val build = Build(configDirectory, loadedProjects, workspaceSettings)
    val state = State(build, client, NoPool, options.common, logger)
    run(state, options)
  }

  @tailrec
  def run(state: State, options: CliOptions): Unit = {
    val origin = state.build.origin
    val config = origin.underlying
    def waitForState(a: Action, t: Task[State]): State = {
      // Ignore the exit status here, all we want is the task to finish execution or fail.
      Cli.waitUntilEndOfWorld(a, options, state.pool, config, state.logger) {
        t.map(s => { State.stateCache.updateBuild(s.copy(status = ExitStatus.Ok)); s.status })
      }

      // Recover the state if the previous task has been successful.
      State.stateCache
        .getStateFor(origin, state.client, state.pool, options.common, state.logger)
        .getOrElse(state)

    }

    val input = reader.readLine()
    input.split(" ") match {
      case Array("exit") =>
        val persistOut = (msg: String) => state.commonOptions.ngout.println(msg)
        waitForState(Exit(ExitStatus.Ok), Task.now(state))
        ()

      case Array("projects") =>
        val action = Run(Commands.Projects(), Exit(ExitStatus.Ok))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("clean") =>
        val allProjects = state.build.loadedProjects.map(_.project.name)
        val action = Run(Commands.Clean(allProjects), Exit(ExitStatus.Ok))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("compile", projectName) =>
        val action = Run(Commands.Compile(List(projectName)), Exit(ExitStatus.Ok))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("compile", "--pipeline", projectName1) =>
        val action = Run(Commands.Compile(List(projectName1), pipeline = true))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("compile", projectName1, projectName2) =>
        val action =
          Run(Commands.Compile(List(projectName1)), Run(Commands.Compile(List(projectName2))))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("console", projectName) =>
        val action = Run(Commands.Console(List(projectName)), Exit(ExitStatus.Ok))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("test", projectName) =>
        val command = Commands.Test(List(projectName))
        val action = Run(command, Exit(ExitStatus.Ok))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("runMain", projectName, mainClass, args @ _*) =>
        val command = Commands.Run(List(projectName), Some(mainClass), args = args.toList)
        val action = Run(command, Exit(ExitStatus.Ok))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case Array("run", projectName, args @ _*) =>
        val command = Commands.Run(List(projectName), None, args = args.toList)
        val action = Run(command, Exit(ExitStatus.Ok))
        run(waitForState(action, Interpreter.execute(action, Task.now(state))), options)

      case _ =>
        val dummyAction =
          Print(s"Not understood: '$input'", state.commonOptions, Exit(ExitStatus.Ok))
        run(waitForState(dummyAction, Task.now(state)), options)
    }
  }

  private def consoleReader(): ConsoleReader = {
    val reader = new ConsoleReader()
    reader.setPrompt("shell> ")
    reader
  }

}
