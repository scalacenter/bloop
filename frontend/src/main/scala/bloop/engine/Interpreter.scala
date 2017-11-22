package bloop.engine

import bloop.cli.{CliOptions, Commands, CommonOptions, ExitStatus}
import bloop.io.{AbsolutePath, Paths}
import bloop.tasks.CompilationTasks
import bloop.{CompilerCache, Project, QuietLogger}
import sbt.internal.inc.bloop.ZincInternals

object Interpreter {
  def execute(action: Action): ExitStatus = action match {
    case Exit(exitStatus) => exitStatus
    case Print(msg, commonOptions, next) =>
      printOut(msg, commonOptions)
      execute(next)
    case Run(Commands.Version(cliOptions), next) =>
      printVersion(cliOptions)
      execute(next)
    case Run(Commands.Clean(projects, cliOptions), next) =>
      clean(projects, cliOptions)
      execute(next)
    case Run(Commands.Compile(projectName, incremental, cliOptions), next) =>
      compile(projectName, incremental, cliOptions)
      execute(next)
  }

  private def printVersion(cliOptions: CliOptions): ExitStatus = {
    cliOptions.common.out.println("bloop 0.1.0 -- To be redone.")
    ExitStatus.Ok
  }

  private def printOut(msg: String, commonOptions: CommonOptions): ExitStatus = {
    commonOptions.out.println(msg)
    ExitStatus.Ok
  }

  private def constructTasks(projects: Map[String, Project]): CompilationTasks = {
    val provider = ZincInternals.getComponentProvider(Paths.getCacheDirectory("components"))
    val compilerCache = new CompilerCache(provider, Paths.getCacheDirectory("scala-jars"))
    CompilationTasks(projects, compilerCache, QuietLogger)
  }

  private def getConfigDir(cliOptions: CliOptions): AbsolutePath = {
    cliOptions.configDir
      .map(AbsolutePath.apply)
      .getOrElse(cliOptions.common.workingPath.resolve(".bloop-config"))
  }

  private def compile(projectName: String,
                      incremental: Boolean,
                      cliOptions: CliOptions): ExitStatus = {
    import scala.concurrent.ExecutionContext.Implicits.global
    val configDir = getConfigDir(cliOptions)
    val projects = Project.fromDir(configDir)
    val tasks = constructTasks(projects)
    val project = projects(projectName)
    if (incremental) {
      tasks.parallelCompile(project)
      ExitStatus.Ok
    } else {
      val newProjects = tasks.clean(projects.keys.toList)
      val newTasks = tasks.copy(initialProjects = newProjects)
      newTasks.parallelCompile(project)
      ExitStatus.Ok
    }
  }

  private def clean(projectNames: List[String], cliOptions: CliOptions): ExitStatus = {
    val configDir = getConfigDir(cliOptions)
    val projects = Project.fromDir(configDir)
    val tasks = constructTasks(projects)
    tasks.clean(projectNames).valuesIterator.map { project =>
      tasks.persistAnalysis(project, QuietLogger)
    }
    ExitStatus.Ok
  }
}
