package bloop.engine

import bloop.bsp.BspServer

import scala.annotation.tailrec
import bloop.cli.{CliOptions, Commands, ExitStatus}
import bloop.io.SourceWatcher
import bloop.io.Timer.timed
import bloop.reporter.ReporterConfig
import bloop.testing.TestInternals
import bloop.engine.tasks.Tasks
import bloop.Project
import monix.eval.Task
import monix.execution.misc.NonFatal

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object Interpreter {
  @tailrec
  def execute(action: Action, state: State): State = {
    def logAndTime(cliOptions: CliOptions, action: Task[State]): State = {
      state.logger.verboseIf(cliOptions.verbose)(timed(state.logger)(waitAndLog(state, action)))
    }

    action match {
      case Exit(exitStatus) if state.status.isOk => state.mergeStatus(exitStatus)
      case Exit(exitStatus) => state
      case Print(msg, commonOptions, next) =>
        state.logger.info(msg)
        execute(next, state)
      case Run(Commands.About(cliOptions), next) =>
        execute(next, logAndTime(cliOptions, printAbout(state)))
      case Run(cmd: Commands.ValidatedBsp, next) =>
        execute(next, logAndTime(cmd.cliOptions, runBsp(cmd, state)))
      case Run(cmd: Commands.Clean, next) =>
        execute(next, logAndTime(cmd.cliOptions, clean(cmd, state)))
      case Run(cmd: Commands.Compile, next) =>
        execute(next, logAndTime(cmd.cliOptions, compile(cmd, state)))
      case Run(cmd: Commands.Console, next) =>
        execute(next, logAndTime(cmd.cliOptions, console(cmd, state)))
      case Run(cmd: Commands.Projects, next) =>
        execute(next, logAndTime(cmd.cliOptions, showProjects(cmd, state)))
      case Run(cmd: Commands.Test, next) =>
        execute(next, logAndTime(cmd.cliOptions, test(cmd, state)))
      case Run(cmd: Commands.Run, next) =>
        execute(next, logAndTime(cmd.cliOptions, run(cmd, state)))
      case Run(cmd: Commands.Configure, next) =>
        execute(next, logAndTime(cmd.cliOptions, configure(cmd, state)))
      case Run(cmd: Commands.Bsp, next) =>
        val msg = "Internal error: command bsp must be validated before use."
        execute(Print(msg, cmd.cliOptions.common, Exit(ExitStatus.UnexpectedError)), state)
    }
  }

  private final val t = "    "
  private final val line = System.lineSeparator()
  private def printAbout(state: State): Task[State] = Task {
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

    state.mergeStatus(ExitStatus.Ok)
  }

  private def runBsp(cmd: Commands.ValidatedBsp, state: State): Task[State] = {
    val scheduler = ExecutionContext.bspScheduler
    BspServer.run(cmd, state, scheduler)
  }

  private def watch(project: Project, state: State, f: State => Task[State]): Task[State] = Task {
    // TODO(jvican): The implementation here could be improved, do so.
    val reachable = Dag.dfs(state.build.getDagFor(project))
    val allSourceDirs = reachable.iterator.flatMap(_.sourceDirectories.toList).map(_.underlying)
    val watcher = new SourceWatcher(allSourceDirs.toList, state.logger)
    // Make file watching cancel tasks if lots of different changes happen in less than 100ms
    val watchFn: State => State = { state => waitAndLog(state, f(state)) }
    val firstOp = watchFn(state)
    watcher.watch(firstOp, watchFn)
  }

  private def compile(cmd: Commands.Compile, state: State): Task[State] = {
    val reporterConfig = ReporterConfig.toFormat(cmd.reporter)

    state.build.getProjectFor(cmd.project) match {
      case Some(project) =>
        def doCompile(state: State): Task[State] = {
          Tasks.compile(state, project, reporterConfig).map(_.mergeStatus(ExitStatus.Ok))
        }

        val initialState = {
          if (cmd.incremental) Task(state)
          else {
            // Clean is isolated because we pass in all the build projects
            Tasks.clean(state, state.build.projects, true)
          }
        }
        initialState.flatMap { state =>
          if (!cmd.watch) doCompile(state)
          else watch(project, state, doCompile _)
        }

      case None =>
        Task(reportMissing(cmd.project :: Nil, state))
    }
  }

  private def showProjects(cmd: Commands.Projects, state: State): Task[State] = Task {
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

  private def console(cmd: Commands.Console, state: State): Task[State] = {
    val reporterConfig = ReporterConfig.toFormat(cmd.reporter)

    state.build.getProjectFor(cmd.project) match {
      case Some(project) =>
        for {
          compiled <- Tasks.compile(state, project, reporterConfig, cmd.excludeRoot)
          result <- Tasks.console(compiled, project, reporterConfig, cmd.excludeRoot)
        } yield result.mergeStatus(ExitStatus.Ok)
      case None =>
        Task(reportMissing(cmd.project :: Nil, state))
    }
  }

  private def test(cmd: Commands.Test, state: State): Task[State] = {
    val reporterConfig = ReporterConfig.toFormat(cmd.reporter)

    Tasks.pickTestProject(cmd.project, state) match {
      case Some(project) =>
        def doTest(state: State): Task[State] = {
          val testFilter = TestInternals.parseFilters(cmd.filter)
          for {
            compiled <- Tasks.compile(state, project, reporterConfig, excludeRoot = false)
            result <- Tasks.test(compiled, project, cmd.isolated, testFilter)
          } yield result
        }
        if (cmd.watch) watch(project, state, doTest _)
        else doTest(state)

      case None =>
        Task(reportMissing(cmd.project :: Nil, state))
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

  private def configure(cmd: Commands.Configure, state: State): Task[State] = Task {
    if (cmd.threads != ExecutionContext.executor.getCorePoolSize)
      State.setCores(state, cmd.threads)
    else state
  }

  private def clean(cmd: Commands.Clean, state: State): Task[State] = {
    val (projects, missing) = lookupProjects(cmd.projects, state)
    if (missing.isEmpty)
      Tasks.clean(state, projects, cmd.isolated).map(_.mergeStatus(ExitStatus.Ok))
    else Task(reportMissing(missing, state))
  }

  private def run(cmd: Commands.Run, state: State): Task[State] = {
    val reporter = ReporterConfig.toFormat(cmd.reporter)

    state.build.getProjectFor(cmd.project) match {
      case Some(project) =>
        def getMainClass(state: State): Option[String] = {
          cmd.main.orElse {
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
        }
        def doRun(state: State): Task[State] = {
          Tasks.compile(state, project, reporter, excludeRoot = false).flatMap { compiled =>
            getMainClass(compiled) match {
              case None =>
                Task(compiled.mergeStatus(ExitStatus.UnexpectedError))
              case Some(main) =>
                val args = cmd.args.toArray
                Tasks.run(compiled, project, main, args)
            }
          }
        }

        if (cmd.watch) watch(project, state, doRun _)
        else doRun(state)

      case None =>
        Task(reportMissing(cmd.project :: Nil, state))
    }
  }

  private def reportMissing(projectNames: List[String], state: State): State = {
    val projects = projectNames.mkString("'", "', '", "'")
    val configDirectory = state.build.origin.syntax
    state.logger.error(s"No projects named $projects found in '$configDirectory'.")
    state.logger.error(s"Use the `projects` command to list existing projects.")
    state.mergeStatus(ExitStatus.InvalidCommandLineOption)
  }

  private def waitAndLog(previousState: State, newState: Task[State]): State = {
    try {
      // Duration has to be infinity, we cannot predict how much time compilation takes
      Await.result(newState.runAsync(previousState.scheduler), Duration.Inf)
    } catch {
      case NonFatal(t) =>
        previousState.logger.error(t.getMessage)
        previousState.logger.trace(t)
        previousState
    }
  }
}
