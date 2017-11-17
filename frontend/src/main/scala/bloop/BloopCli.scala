package bloop

import bloop.cli.Commands
import bloop.io.IO
import bloop.tasks.CompilationTasks
import caseapp.{CommandApp, RemainingArgs}
import sbt.internal.inc.bloop.ZincInternals

object BloopCli extends CommandApp[Commands.Command] {
  // TODO: To be filled in via `BuildInfo`.
  override def appName: String = "bloop"
  override def appVersion: String = "0.1.0"

  override def run(command: Commands.Command, remainingArgs: RemainingArgs): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    command match {
      case Commands.Compile(config, projectName, batch, parallel) =>
        val projects = Project.fromDir(config)
        val componentProvider =
          ZincInternals.getComponentProvider(IO.getCacheDirectory("components"))
        val compilerCache = new CompilerCache(componentProvider, IO.getCacheDirectory("scala-jars"))
        val project = projects(projectName)
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)

        // TODO: Handle the case where it's batch and sequential
        if (parallel && batch) tasks.parallelNaive(project)
        else if (parallel) tasks.parallel(project)
        else tasks.sequential(project)

        ()
      case Commands.Clean(config, projects) => () // Nothing for now
    }
  }
}
