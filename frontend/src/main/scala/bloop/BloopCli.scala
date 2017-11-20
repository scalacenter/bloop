package bloop

import java.nio.file.Path

import bloop.io.{AbsolutePath, Paths}
import bloop.cli.{Command, Commands}
import bloop.cli.CustomCaseAppParsers.fileParser
import bloop.tasks.CompilationTasks
import caseapp.{CommandApp, RemainingArgs}
import sbt.internal.inc.bloop.ZincInternals

object BloopCli extends CommandApp[Command] {
  // TODO: To be filled in via `BuildInfo`.
  override def appName: String = "bloop"
  override def appVersion: String = "0.1.0"

  def readAllProjects(baseDir: Path): Map[String, Project] = {
    // TODO: Control here and in `compile` the cwd used.
    val baseDirectory = AbsolutePath(baseDir)
    val configDirectory = baseDirectory.resolve(".bloop-config")
    Project.fromDir(configDirectory)
  }

  def constructTasks(projects: Map[String, Project]): CompilationTasks = {
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"))
    CompilationTasks(projects, compilerCache, QuietLogger)
  }

  // TODO: Remove all the boilerplate that arises from reading the config file and cache it.
  override def run(command: Command, remainingArgs: RemainingArgs): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    command match {
      case Commands.Compile(baseDir, projectName, incremental) =>
        val projects = readAllProjects(baseDir)
        val tasks = constructTasks(projects)
        val project = projects(projectName)
        if (incremental) tasks.parallelCompile(project)
        else {
          val newProjects = tasks.clean(projects.keys.toList)
          val newTasks = tasks.copy(projects = newProjects)
          newTasks.parallelCompile(project)
        }
        ()

      case Commands.Clean(baseDir, projectNames) =>
        val projects = readAllProjects(baseDir)
        val tasks = constructTasks(projects)
        tasks.clean(projects.keys.toList).valuesIterator.map { project =>
          tasks.persistAnalysis(project, QuietLogger)
        }
        ()
    }
  }
}
