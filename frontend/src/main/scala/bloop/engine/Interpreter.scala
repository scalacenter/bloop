package bloop.engine

import bloop.cli.{CliOptions, Commands, CommonOptions, ExitStatus}
import bloop.io.Timer.timed
import bloop.reporter.ReporterConfig
import bloop.engine.tasks.{CompileTasks, TestTasks}
import bloop.Project

object Interpreter {
  def execute(action: Action, state: State): State = {
    import state.logger
    def logAndTime[T](cliOptions: CliOptions, action: => T): T =
      logger.verboseIf(cliOptions.verbose) { timed(state.logger) { action } }
    action match {
      case Exit(exitStatus) if state.status.isOk => state.copy(status = exitStatus)
      case Exit(exitStatus) => state
      case Print(msg, commonOptions, next) =>
        val status = printOut(msg, commonOptions)
        execute(next, state.mergeStatus(status))
      case Run(Commands.About(cliOptions), next) =>
        val status = logger.verboseIf(cliOptions.verbose) { printAbout(cliOptions) }
        execute(next, state.mergeStatus(status))
      case Run(cmd: Commands.Clean, next) =>
        execute(next, logAndTime(cmd.cliOptions, clean(cmd, state)))
      case Run(cmd: Commands.Compile, next) =>
        execute(next, logAndTime(cmd.cliOptions, compile(cmd, state)))
      case Run(cmd: Commands.Projects, next) =>
        execute(next, logAndTime(cmd.cliOptions, showProjects(cmd, state)))
      case Run(cmd: Commands.Test, next) if cmd.prependCompile =>
        val compile = Commands.Compile(cmd.project, true, cmd.scalacstyle, cmd.cliOptions)
        execute(Run(compile, Run(cmd.copy(prependCompile = false), next)), state)
      case Run(cmd: Commands.Test, next) =>
        execute(next, logAndTime(cmd.cliOptions, test(cmd, state)))
    }
  }

  private final val t = "    "
  private def printAbout(cliOptions: CliOptions): ExitStatus = {
    val bloopName = bloop.internal.build.BuildInfo.name
    val bloopVersion = bloop.internal.build.BuildInfo.version
    val scalaVersion = bloop.internal.build.BuildInfo.scalaVersion
    val zincVersion = bloop.internal.build.BuildInfo.zincVersion
    val developers = bloop.internal.build.BuildInfo.developers.mkString(", ")
    val header =
      s"""|$t   _____            __         ______           __
          |$t  / ___/_________ _/ /___ _   / ____/__  ____  / /____  _____
          |$t  \\__ \\/ ___/ __ `/ / __ `/  / /   / _ \\/ __ \\/ __/ _ \\/ ___/
          |$t ___/ / /__/ /_/ / / /_/ /  / /___/ /__/ / / / /_/ /__/ /
          |$t/____/\\___/\\__,_/_/\\__,_/   \\____/\\___/_/ /_/\\__/\\___/_/
          |""".stripMargin
    val versions = s"""
                      |$t${bloopName.capitalize} version    `$bloopVersion`
                      |${t}Zinc version     `$zincVersion`
                      |${t}Scala version    `$scalaVersion`""".stripMargin
    cliOptions.common.out.println(header)
    cliOptions.common.out.println(t) // This is the only way to add newline, otherwise ignored
    cliOptions.common.out.println(s"$t$bloopName is made with love at the Scala Center <3")
    cliOptions.common.out.println(t)
    cliOptions.common.out.println(versions)
    cliOptions.common.out.println(t)
    cliOptions.common.out.println(s"${t}It is maintained by $developers.")

    ExitStatus.Ok
  }

  private def printOut(msg: String, commonOptions: CommonOptions): ExitStatus = {
    commonOptions.out.println(msg)
    ExitStatus.Ok
  }

  private def compile(cmd: Commands.Compile, state: State): State = {
    val reporterConfig = ReporterConfig.getDefault(cmd.scalacstyle)
    def runCompile(project: Project): State = {
      if (cmd.incremental) {
        CompileTasks.compile(state, project, reporterConfig).mergeStatus(ExitStatus.Ok)
      } else {
        val newState = CompileTasks.clean(state, state.build.projects)
        CompileTasks.compile(newState, project, reporterConfig).mergeStatus(ExitStatus.Ok)
      }
    }

    state.build.getProjectFor(cmd.project) match {
      case Some(project) => runCompile(project)
      case None => reportMissing(cmd.project :: Nil, state)
    }
  }

  private def showProjects(cmd: Commands.Projects, state: State): State = {
    if (cmd.dotGraph) {
      val contents = Dag.toDotGraph(state.build.dags)
      printOut(contents, cmd.cliOptions.common)
    } else {
      // TODO: Pretty print output of show projects, please.
      val configDirectory = state.build.origin.syntax
      state.logger.info(s"Projects loaded from '$configDirectory':")
      state.build.projects.map(_.name).sorted.foreach { projectName =>
        state.logger.info(s" * $projectName")
      }
    }

    state.mergeStatus(ExitStatus.Ok)
  }

  private def test(cmd: Commands.Test, state: State): State = {
    val projectToTest = TestTasks.pickTestProject(cmd.project, state)
    projectToTest match {
      case Some(project) => TestTasks.test(state, project, cmd.aggregate)
      case None => reportMissing(cmd.project :: Nil, state)
    }
  }

  type ProjectLookup = (List[Project], List[String])
  private def lookupProjects(names: List[String], state: State): ProjectLookup = {
    val build = state.build
    val result = List[Project]() -> List[String]()
    names.foldLeft(result) {
      case ((projects, missing), name) =>
        build.getProjectFor(name) match {
          case Some(project) => (project :: projects) -> missing
          case None => projects -> (name :: missing)
        }
    }
  }

  private def clean(cmd: Commands.Clean, state: State): State = {
    val (projects, missing) = lookupProjects(cmd.projects, state)
    if (missing.isEmpty) CompileTasks.clean(state, projects).mergeStatus(ExitStatus.Ok)
    else reportMissing(missing, state)
  }

  private def reportMissing(projectNames: List[String], state: State): State = {
    val projects = projectNames.mkString("'", "', '", "'")
    val configDirectory = state.build.origin.syntax
    state.logger.error(s"No projects named $projects found in '$configDirectory'.")
    state.logger.error(s"Use the `projects` command to list existing projects.")
    state.mergeStatus(ExitStatus.InvalidCommandLineOption)
  }
}
