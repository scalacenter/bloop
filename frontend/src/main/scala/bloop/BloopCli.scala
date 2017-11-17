package bloop

import bloop.io.IO
import bloop.cli.{Command, Commands}
import bloop.cli.CustomCaseAppParsers._
import bloop.tasks.CompilationTasks

import caseapp.{CommandApp, RemainingArgs}
import sbt.internal.inc.bloop.ZincInternals

object BloopCli extends CommandApp[Command] {
  // TODO: To be filled in via `BuildInfo`.
  override def appName: String = "bloop"
  override def appVersion: String = "0.1.0"

  // TODO: Remove all the boilerplate that arises from reading the config file and cache it.
  override def run(command: Command, remainingArgs: RemainingArgs): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    command match {
      case Commands.Compile(baseDir, projectName, batch, parallel) =>
        val projects = Project.fromDir(baseDir)
        val provider = ZincInternals.getComponentProvider(IO.getCacheDirectory("components"))
        val compilerCache = new CompilerCache(provider, IO.getCacheDirectory("scala-jars"))
        val project = projects(projectName)
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)

        // TODO: Handle the corner case where it's batch and sequential
        if (parallel && batch) tasks.parallelNaive(project)
        else if (parallel) tasks.parallel(project)
        else tasks.sequential(project)

        ()
      case Commands.Clean(baseDir, projectNames) =>
        val projects = Project.fromDir(baseDir)
        val provider = ZincInternals.getComponentProvider(IO.getCacheDirectory("components"))
        val compilerCache = new CompilerCache(provider, IO.getCacheDirectory("scala-jars"))
        val tasks = new CompilationTasks(projects, compilerCache, QuietLogger)
        tasks.clean(projects.keys.toList).valuesIterator.map { project =>
          tasks.persistAnalysis(project, QuietLogger)
        }
    }
  }
}
