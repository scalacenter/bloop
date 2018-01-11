package bloop.engine

import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.io.SourceWatcher
import bloop.io.Timer.timed
import bloop.reporter.ReporterConfig
import bloop.engine.tasks.Tasks
import bloop.Project
import bloop.bsp.BloopBspServices

object Interpreter {
  def execute(action: Action, state: State): State = {
    def logAndTime[T](state: State, cliOptions: CliOptions, action: => T): T =
      state.logger.verboseIf(cliOptions.verbose) { timed(state.logger) { action } }

    action match {
      case Exit(exitStatus) if state.status.isOk => state.mergeStatus(exitStatus)
      case Exit(exitStatus) => state
      case Print(msg, commonOptions, next) =>
        state.logger.info(msg)
        execute(next, state)
      case Run(Commands.About(cliOptions), next) =>
        val status = logAndTime(state, cliOptions, printAbout(state))
        execute(next, state.mergeStatus(status))
      case Run(Commands.Bsp(cliOptions), next) =>
        val status = logAndTime(state, cliOptions, runBsp(state))
        execute(next, state.mergeStatus(status))
      case Run(cmd: Commands.Clean, next) =>
        execute(next, logAndTime(state, cmd.cliOptions, clean(cmd, state)))
      case Run(cmd: Commands.Compile, next) =>
        execute(next, logAndTime(state, cmd.cliOptions, compile(cmd, state)))
      case Run(cmd: Commands.Console, next) =>
        execute(next, logAndTime(state, cmd.cliOptions, console(cmd, state)))
      case Run(cmd: Commands.Projects, next) =>
        execute(next, logAndTime(state, cmd.cliOptions, showProjects(cmd, state)))
      case Run(cmd: Commands.Test, next) =>
        execute(next, logAndTime(state, cmd.cliOptions, test(cmd, state)))
      case Run(cmd: Commands.Run, next) =>
        execute(next, logAndTime(state, cmd.cliOptions, run(cmd, state)))
      case Run(cmd: Commands.Configure, next) =>
        execute(next, logAndTime(state, cmd.cliOptions, configure(cmd, state)))
    }
  }

  private final val t = "    "
  private final val line = System.lineSeparator()
  private def printAbout(state: State): ExitStatus = {
    import state.logger
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
    logger.info(s"$header$line")
    logger.info(s"$t$bloopName is made with love at the Scala Center <3$line")
    logger.info(s"$versions$line$line") // I have no idea why two are required, one is not enough
    logger.info(s"${t}It is maintained by $developers.")

    ExitStatus.Ok
  }

  private def runBsp(state: State): ExitStatus = {
    import org.langmeta.lsp.LanguageClient
    import org.langmeta.lsp.LanguageServer
    import org.langmeta.jsonrpc.BaseProtocolMessage
    val dummyLogger = com.typesafe.scalalogging.Logger(this.getClass)
    val client = new LanguageClient(state.commonOptions.out, dummyLogger)
    val services = new BloopBspServices(state, client).services
    val messages = BaseProtocolMessage.fromInputStream(state.commonOptions.in)
    val scheduler = ExecutionContext.bspScheduler
    val server = new LanguageServer(messages, client, services, scheduler, dummyLogger)
    try { server.listen(); ExitStatus.Ok } catch {
      case scala.util.control.NonFatal(t) =>
        state.logger.trace(t)
        ExitStatus.UnexpectedError
    }
  }

  private def watch(project: Project, state: State, f: State => State): State = {
    val reachable = Dag.dfs(state.build.getDagFor(project))
    val allSourceDirs = reachable.iterator.flatMap(_.sourceDirectories.toList).map(_.underlying)
    val watcher = new SourceWatcher(allSourceDirs.toList, state.logger)
    // Force the first operation and then allow the watcher to execute it in next steps
    watcher.watch(f(state), f)
  }

  private def compile(cmd: Commands.Compile, state: State): State = {
    val reporterConfig = ReporterConfig.toFormat(cmd.reporter)
    def runCompile(project: Project): State = {
      def doCompile(state: State): State =
        Tasks.compile(state, project, reporterConfig).mergeStatus(ExitStatus.Ok)
      if (cmd.incremental) {
        if (!cmd.watch) doCompile(state)
        else watch(project, state, doCompile _)
      } else {
        // Clean is isolated because we pass in all the build projects
        val newState = Tasks.clean(state, state.build.projects, true)
        if (!cmd.watch) doCompile(newState)
        else watch(project, newState, doCompile _)
      }
    }

    state.build.getProjectFor(cmd.project) match {
      case Some(project) => runCompile(project)
      case None => reportMissing(cmd.project :: Nil, state)
    }
  }

  private def showProjects(cmd: Commands.Projects, state: State): State = {
    import state.logger
    if (cmd.dotGraph) {
      val contents = Dag.toDotGraph(state.build.dags)
      logger.info(contents)
    } else {
      // TODO: Pretty print output of show projects, please.
      val configDirectory = state.build.origin.syntax
      logger.info(s"Projects loaded from '$configDirectory':")
      state.build.projects.map(_.name).sorted.foreach { projectName =>
        logger.info(s" * $projectName")
      }
    }

    state.mergeStatus(ExitStatus.Ok)
  }

  private def console(cmd: Commands.Console, state: State): State = {
    val reporterConfig = ReporterConfig.toFormat(cmd.reporter)
    def runConsole(project: Project) =
      Tasks.console(state, project, reporterConfig, cmd.excludeRoot)

    state.build.getProjectFor(cmd.project) match {
      case Some(project) => runConsole(project).mergeStatus(ExitStatus.Ok)
      case None => reportMissing(cmd.project :: Nil, state)
    }
  }

  private def test(cmd: Commands.Test, state: State): State = {
    def compileAndTest(state: State, project: Project): State = {
      def run(state: State) = {
        // Note that we always compile incrementally for test execution
        val reporter = ReporterConfig.toFormat(cmd.reporter)
        val state1 = Tasks.compile(state, project, reporter)
        Tasks.test(state1, project, cmd.isolated)
      }

      if (!cmd.watch) run(state)
      else watch(project, state, run _)
    }

    val projectToTest = Tasks.pickTestProject(cmd.project, state)
    projectToTest match {
      case Some(project) => compileAndTest(state, project)
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

  private def configure(cmd: Commands.Configure, state: State): State = {
    if (cmd.threads != ExecutionContext.executor.getCorePoolSize)
      State.setCores(state, cmd.threads)
    else state
  }

  private def clean(cmd: Commands.Clean, state: State): State = {
    val (projects, missing) = lookupProjects(cmd.projects, state)
    if (missing.isEmpty) Tasks.clean(state, projects, cmd.isolated).mergeStatus(ExitStatus.Ok)
    else reportMissing(missing, state)
  }

  private def run(cmd: Commands.Run, state: State): State = {
    val reporter = ReporterConfig.toFormat(cmd.reporter)
    def getMainClass(state: State, project: Project): Option[String] = {
      Tasks.findMainClasses(state, project) match {
        case Array() =>
          state.logger.error(s"No main classes found in project '${project.name}'")
          None
        case Array(main) =>
          Some(main)
        case mainClasses =>
          val eol = System.lineSeparator
          val message = s"""Several main classes were found, specify which one:
                           |${mainClasses.mkString(" * ", s"$eol * ", "")}""".stripMargin
          state.logger.error(message)
          None
      }
    }
    def run(project: Project): State = {
      def doRun(state: State): State = {
        val compiledState = Tasks.compile(state, project, reporter)
        val selectedMainClass = cmd.main.orElse(getMainClass(compiledState, project))
        selectedMainClass
          .map { main =>
            val args = cmd.args.toArray
            Tasks.run(compiledState, project, main, args)
          }
          .getOrElse(compiledState.mergeStatus(ExitStatus.UnexpectedError))
      }

      if (!cmd.watch) doRun(state)
      else watch(project, state, doRun _)
    }

    state.build.getProjectFor(cmd.project) match {
      case Some(project) => run(project)
      case None => reportMissing(cmd.project :: Nil, state)
    }
  }

  private def reportMissing(projectNames: List[String], state: State): State = {
    val projects = projectNames.mkString("'", "', '", "'")
    val configDirectory = state.build.origin.syntax
    state.logger.error(s"No projects named $projects found in '$configDirectory'.")
    state.logger.error(s"Use the `projects` command to list existing projects.")
    state.mergeStatus(ExitStatus.InvalidCommandLineOption)
  }
}
