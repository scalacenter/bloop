package bloop.engine

import bloop.CompileMode
import bloop.bsp.BspServer
import bloop.cli._
import bloop.cli.CliParsers.CommandsMessages
import bloop.cli.completion.{Case, Mode}
import bloop.io.{AbsolutePath, RelativePath, SourceWatcher}
import bloop.logging.{DebugFilter, Logger}
import bloop.testing.{LoggingEventHandler, TestInternals}
import bloop.engine.tasks.{CompileTask, LinkTask, Tasks, TestTask}
import bloop.cli.Commands.CompilingCommand
import bloop.cli.Validate
import bloop.data.{Platform, Project, ClientInfo}
import bloop.engine.Feedback.XMessageString
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import bloop.reporter.{ReporterInputs, LogReporter}

import caseapp.core.CommandMessages
import monix.eval.Task

import scala.concurrent.Promise

object Interpreter {
  // This is stack-safe because of Monix's trampolined execution
  def execute(action: Action, stateTask: Task[State]): Task[State] = {
    def execute(action: Action, stateTask: Task[State]): Task[State] = {
      stateTask.flatMap { state =>
        action match {
          // We keep it case because there is a 'match may not be exhaustive' false positive by scalac
          // Looks related to existing bug report https://github.com/scala/bug/issues/10251
          case Exit(exitStatus: ExitStatus) if exitStatus.isOk =>
            Task.now(state.mergeStatus(exitStatus))
          case Exit(exitStatus: ExitStatus) => Task.now(state.mergeStatus(exitStatus))
          case Print(msg, _, next) =>
            state.logger.info(msg)
            execute(next, Task.now(state))
          case Run(cmd: Commands.Bsp, _) =>
            val msg = "Internal error: The `bsp` command must be validated before use"
            val printAction = Print(msg, cmd.cliOptions.common, Exit(ExitStatus.UnexpectedError))
            execute(printAction, Task.now(state))
          case Run(cmd: Commands.ValidatedBsp, next) =>
            execute(next, runBsp(cmd, state))
          case Run(Commands.About(_), next) =>
            execute(next, printAbout(state))
          case Run(cmd: Commands.Help, next) =>
            val msg = "The handling of `help` does not happen in the `Interpreter`"
            val printAction =
              Print(msg, cmd.cliOptions.common, Exit(ExitStatus.UnexpectedError))
            execute(printAction, Task.now(state))
          case Run(cmd: Commands.Command, next) =>
            // We validate for almost all commands coming from the CLI except for BSP and about,help
            Validate.validateBuildForCLICommands(state, state.logger.error(_)).flatMap { state =>
              // Don't continue the interpretation if a build-related error has been reported
              if (state.status == ExitStatus.BuildDefinitionError) Task.now(state)
              else {
                cmd match {
                  case cmd: Commands.Clean =>
                    execute(next, clean(cmd, state))
                  case cmd: Commands.Compile =>
                    execute(next, compile(cmd, state))
                  case cmd: Commands.Console =>
                    execute(next, console(cmd, state))
                  case cmd: Commands.Projects =>
                    execute(next, showProjects(cmd, state))
                  case cmd: Commands.Test =>
                    execute(next, test(cmd, state))
                  case cmd: Commands.Run =>
                    execute(next, run(cmd, state))
                  case cmd: Commands.Configure =>
                    execute(next, configure(cmd, state))
                  case cmd: Commands.Autocomplete =>
                    execute(next, autocomplete(cmd, state))
                  case cmd: Commands.Link =>
                    execute(next, link(cmd, state))
                }
              }
            }
        }
      }
    }

    execute(action, stateTask)
  }

  private final val t = "    "
  private final val line = System.lineSeparator()
  private def printAbout(state: State): Task[State] = Task {
    import state.logger
    val bloopName = bloop.internal.build.BuildInfo.bloopName
    val bloopVersion = bloop.internal.build.BuildInfo.version
    val scalaVersion = bloop.internal.build.BuildInfo.scalaVersion
    val zincVersion = bloop.internal.build.BuildInfo.zincVersion
    val developers = bloop.internal.build.BuildInfo.developers.mkString(", ")

    logger.info(s"$bloopName v$bloopVersion")
    logger.info("")
    logger.info(s"Running on Scala v$scalaVersion and Zinc v$zincVersion")
    logger.info(s"Maintained by the Scala Center ($developers)")

    state.mergeStatus(ExitStatus.Ok)
  }

  private def getProjectsDag(projects: List[Project], state: State): Dag[Project] =
    Aggregate(projects.map(p => state.build.getDagFor(p)))

  private def runBsp(cmd: Commands.ValidatedBsp, state: State): Task[State] = {
    BspServer.run(cmd, state, RelativePath(".bloop"), None, None, ExecutionContext.ioScheduler)
  }

  private[bloop] def watch(projects: List[Project], state: State)(
      f: State => Task[State]
  ): Task[State] = {
    val reachable = Dag.dfs(getProjectsDag(projects, state))
    val allSources = reachable.iterator.flatMap(_.sources.toList).map(_.underlying)
    val watcher = SourceWatcher(projects.map(_.name), allSources.toList, state.logger)
    val fg = (state: State) =>
      f(state).map { state =>
        watcher.notifyWatch()
        State.stateCache.updateBuild(state)
      }

    if (!BspServer.isWindows)
      state.logger.info("\u001b[H\u001b[2J")
    // Force the first execution before relying on the file watching task
    fg(state).flatMap(newState => watcher.watch(newState, fg))
  }

  private def runCompile(
      cmd: CompilingCommand,
      state0: State,
      projects: List[Project],
      excludeRoot: Boolean = false
  ): Task[State] = {
    // Make new state cleaned of all compilation products if compilation is not incremental
    val state: Task[State] = {
      if (cmd.incremental) Task.now(state0)
      else Tasks.clean(state0, state0.build.projects, true)
    }

    val compilerMode: CompileMode.ConfigurableMode = CompileMode.Sequential
    val compileTask = state.flatMap { state =>
      val config = ReporterKind.toReporterConfig(cmd.reporter)
      val dag = getProjectsDag(projects, state)
      val createReporter = (inputs: ReporterInputs[Logger]) =>
        new LogReporter(inputs.project, inputs.logger, inputs.cwd, config)
      CompileTask.compile(
        state,
        dag,
        createReporter,
        compilerMode,
        cmd.pipeline,
        excludeRoot,
        Promise[Unit](),
        state.logger
      )
    }

    compileTask.map(_.mergeStatus(ExitStatus.Ok))
  }

  private def compile(cmd: Commands.Compile, state: State): Task[State] = {
    val lookup = lookupProjects(cmd.projects, state, state.build.getProjectFor(_))
    if (lookup.missing.nonEmpty) Task.now(reportMissing(lookup.missing, state))
    else {
      val projects: List[Project] = {
        if (!cmd.cascade) lookup.found
        else Dag.inverseDependencies(state.build.dags, lookup.found).reduced
      }

      if (!cmd.watch) runCompile(cmd, state, projects)
      else watch(projects, state)(runCompile(cmd, _, projects))
    }
  }

  private def showProjects(cmd: Commands.Projects, state: State): Task[State] = Task {
    import state.logger
    if (cmd.dotGraph) {
      val contents = Dag.toDotGraph(state.build.dags)
      logger.info(contents)
    } else {
      val configDirectory = state.build.origin.syntax
      logger.debug(s"Projects loaded from '$configDirectory':")(DebugFilter.All)
      state.build.projects.map(_.name).sorted.foreach(logger.info)
    }

    state.mergeStatus(ExitStatus.Ok)
  }

  private def compileAnd[C <: CompilingCommand](
      cmd: C,
      state: State,
      projects: List[Project],
      excludeRoot: Boolean,
      nextAction: String
  )(next: State => Task[State]): Task[State] = {
    runCompile(cmd, state, projects, excludeRoot).flatMap { compiled =>
      if (compiled.status != ExitStatus.CompilationError) next(compiled)
      else {
        val projectsString = projects.mkString(", ")
        Task.now(
          state.withDebug(s"Failed compilation for $projectsString. Skipping $nextAction...")(
            DebugFilter.Compilation
          )
        )
      }
    }
  }

  private def console(cmd: Commands.Console, state: State): Task[State] = {
    cmd.projects match {
      case Nil =>
        Task.now(
          state
            .withError(s"Missing project in `console` invocation")
            .mergeStatus(ExitStatus.InvalidCommandLineOption)
        )
      case project :: Nil =>
        state.build.getProjectFor(project) match {
          case Some(project) =>
            compileAnd(cmd, state, List(project), cmd.excludeRoot, "`console`")(
              state => Tasks.console(state, project, cmd.excludeRoot)
            )
          case None => Task.now(reportMissing(project :: Nil, state))
        }
      case projects =>
        val stringProjects = projects.map(p => s"'$p'").mkString(", ")
        Task.now(
          state
            .withError(s"Unexpected list of projects in `console` invocation: $stringProjects")
            .mergeStatus(ExitStatus.InvalidCommandLineOption)
        )
    }
  }

  private def test(cmd: Commands.Test, state: State): Task[State] = {
    import state.logger
    val lookup = lookupProjects(cmd.projects, state, Tasks.pickTestProject(_, state))
    if (lookup.missing.nonEmpty) Task.now(reportMissing(lookup.missing, state))
    else {
      // Projects to test != projects that need compiling
      val (projectsToCompile, projectsToTest) = {
        if (!cmd.cascade) {
          val projects = lookup.found
          val projectsToTest = {
            if (!cmd.includeDependencies) projects
            else projects.flatMap(p => Dag.dfs(state.build.getDagFor(p)))
          }

          (projects, projectsToTest)
        } else {
          val result = Dag.inverseDependencies(state.build.dags, lookup.found)
          (result.reduced, result.strictlyInverseNodes)
        }
      }

      logger.debug(
        s"Test command will compile ${projectsToCompile.mkString(", ")} transitively"
      )(DebugFilter.Test)

      logger.debug(
        s"Test command will test ${projectsToTest.mkString(", ")}"
      )(DebugFilter.Test)

      def testAllProjects(state: State): Task[State] = {
        val testFilter = TestInternals.parseFilters(cmd.only)
        compileAnd(cmd, state, projectsToCompile, false, "`test`") { state =>
          val handler = new LoggingEventHandler(state.logger)
          Tasks.test(state, projectsToTest, cmd.args, testFilter, handler)
        }
      }

      if (!cmd.watch) testAllProjects(state)
      else watch(projectsToCompile, state)(testAllProjects(_))
    }
  }

  private case class ProjectLookup(found: List[Project], missing: List[String])
  private def lookupProjects(
      names: List[String],
      state: State,
      lookup: String => Option[Project]
  ): ProjectLookup = {
    val build = state.build
    val result = List[Project]() -> List[String]()
    val (found, missing) = names.foldLeft(result) {
      case ((projects, missing), name) =>
        lookup(name) match {
          case Some(project) => (project :: projects) -> missing
          case None => projects -> (name :: missing)
        }
    }

    ProjectLookup(found, missing)
  }

  private def autocomplete(cmd: Commands.Autocomplete, state: State): Task[State] = {
    cmd.mode match {
      case Mode.ProjectBoundCommands =>
        Task {
          val commandsAcceptingProjects = CommandsMessages.messages.collect {
            case (name, CommandMessages(args, _)) if args.exists(_.name == "projects") => name
          }

          state.withInfo(commandsAcceptingProjects.mkString(" "))
        }
      case Mode.Commands =>
        Task {
          for {
            (name, args) <- CommandsMessages.messages
            completion <- cmd.format.showCommand(name, args)
          } state.logger.info(completion)
          state
        }
      case Mode.Projects =>
        Task {
          for {
            project <- state.build.projects
            completion <- cmd.format.showProject(project)
          } state.logger.info(completion)
          state
        }
      case Mode.Flags =>
        Task {
          for {
            command <- cmd.command
            message <- CommandsMessages.messages.toMap.get(command)
            arg <- message.args
            completion <- cmd.format.showArg(command, Case.kebabizeArg(arg))
          } state.logger.info(completion)
          state
        }
      case Mode.Reporters =>
        Task {
          for {
            reporter <- ReporterKind.reporters
            completion <- cmd.format.showReporter(reporter)
          } state.logger.info(completion)
          state
        }
      case Mode.Protocols =>
        Task {
          for {
            protocol <- BspProtocol.protocols
            completion <- cmd.format.showProtocol(protocol)
          } state.logger.info(completion)
          state
        }
      case Mode.MainsFQCN =>
        Task {
          for {
            projectName <- cmd.project
            project <- state.build.getProjectFor(projectName)
            main <- Tasks.findMainClasses(state, project)
            completion <- cmd.format.showMainName(main)
          } state.logger.info(completion)
          state
        }
      case Mode.TestsFQCN =>
        val printTestTask = for {
          projectName <- cmd.project
          project <- Tasks.pickTestProject(projectName, state)
        } yield {
          TestTask.findFullyQualifiedTestNames(project, state).map { testsFqcn =>
            for {
              testFqcn <- testsFqcn
              completion <- cmd.format.showTestName(testFqcn)
            } state.logger.info(completion)
            state
          }
        }

        printTestTask.getOrElse(Task.now(state))
    }
  }

  private def configure(cmd: Commands.Configure, state: State): Task[State] = Task {
    if (cmd.threads == Commands.DefaultThreadNumber) state
    else {
      // Don't support dynamic thread configuration because underlying thread pools sometimes can't
      state.withWarn(s"Dynamic thread configuration has been deprecated and is a no-op.")
    }
  }

  private def clean(cmd: Commands.Clean, state: State): Task[State] = {
    if (cmd.projects.isEmpty) {
      Tasks
        .clean(state, state.build.projects, cmd.includeDependencies)
        .map(_.mergeStatus(ExitStatus.Ok))
    } else {
      val lookup = lookupProjects(cmd.projects, state, state.build.getProjectFor(_))
      if (!lookup.missing.isEmpty)
        Task.now(reportMissing(lookup.missing, state))
      else {
        val projects: List[Project] = {
          if (!cmd.cascade) lookup.found
          else Dag.inverseDependencies(state.build.dags, lookup.found).reduced
        }

        Tasks
          .clean(state, lookup.found, cmd.includeDependencies)
          .map(_.mergeStatus(ExitStatus.Ok))
      }
    }
  }

  private[bloop] def link(cmd: Commands.Link, state: State): Task[State] = {
    def doLink(project: Project)(state: State): Task[State] = {
      compileAnd(cmd, state, List(project), false, "`link`") { state =>
        getMainClass(state, project, cmd.main) match {
          case Left(state) => Task.now(state)
          case Right(mainClass) =>
            project.platform match {
              case platform @ Platform.Native(config, _, _) =>
                val target = ScalaNativeToolchain.linkTargetFrom(project, config)
                LinkTask.linkMainWithNative(cmd, project, state, mainClass, target, platform)

              case platform @ Platform.Js(config, _, _) =>
                val target = ScalaJsToolchain.linkTargetFrom(project, config)
                LinkTask.linkMainWithJs(cmd, project, state, mainClass, target, platform)

              case Platform.Jvm(_, _, _) =>
                val msg = Feedback.noLinkFor(project)
                Task.now(state.withError(msg, ExitStatus.InvalidCommandLineOption))
            }
        }
      }
    }

    val lookup = lookupProjects(cmd.projects, state, state.build.getProjectFor(_))
    if (lookup.missing.nonEmpty)
      Task.now(reportMissing(lookup.missing, state))
    else {
      val projects = lookup.found
      def linkTask(state: State): Task[State] = {
        projects.foldLeft(Task.now(state)) {
          case (stateTask, p) => stateTask.flatMap(doLink(p)(_))
        }
      }

      if (cmd.watch) watch(projects, state)(linkTask) else linkTask(state)
    }
  }

  private def run(cmd: Commands.Run, state: State): Task[State] = {
    def doRun(project: Project)(state: State): Task[State] = {
      val cwd = project.baseDirectory
      compileAnd(cmd, state, List(project), false, "`run`") { state =>
        getMainClass(state, project, cmd.main) match {
          case Left(failedState) => Task.now(failedState)
          case Right(mainClass) =>
            project.platform match {
              case platform @ Platform.Native(config, _, _) =>
                val target = ScalaNativeToolchain.linkTargetFrom(project, config)
                LinkTask
                  .linkMainWithNative(cmd, project, state, mainClass, target, platform)
                  .flatMap { state =>
                    val args = (target.syntax +: cmd.args).toArray
                    if (!state.status.isOk) Task.now(state)
                    else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
                  }
              case platform @ Platform.Js(config, _, _) =>
                val target = ScalaJsToolchain.linkTargetFrom(project, config)
                LinkTask.linkMainWithJs(cmd, project, state, mainClass, target, platform).flatMap {
                  state =>
                    // We use node to run the program (is this a special case?)
                    val args = ("node" +: target.syntax +: cmd.args).toArray
                    if (!state.status.isOk) Task.now(state)
                    else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
                }
              case Platform.Jvm(javaEnv, _, _) =>
                Tasks.runJVM(
                  state,
                  project,
                  javaEnv,
                  cwd,
                  mainClass,
                  cmd.args.toArray,
                  cmd.skipJargs
                )
            }
        }
      }
    }

    val lookup = lookupProjects(cmd.projects, state, state.build.getProjectFor(_))
    if (lookup.missing.nonEmpty) Task.now(reportMissing(lookup.missing, state))
    else {
      val projects = lookup.found
      def runTask(state: State): Task[State] = {
        projects.foldLeft(Task.now(state)) {
          case (stateTask, p) => stateTask.flatMap(doRun(p)(_))
        }
      }

      if (cmd.watch) watch(projects, state)(runTask) else runTask(state)
    }
  }

  private def reportMissing(projectNames: List[String], state: State): State = {
    val projects = projectNames.mkString("'", "', '", "'")
    val configDirectory = state.build.origin.syntax
    state
      .withError(s"No projects named $projects were found in '$configDirectory'")
      .withError(s"Use the `projects` command to list all existing projects")
      .mergeStatus(ExitStatus.InvalidCommandLineOption)
  }

  private[bloop] def getMainClass(
      state: State,
      project: Project,
      cliMainClass: Option[String]
  ): Either[State, String] = {
    cliMainClass match {
      case Some(userMainClass) => Right(userMainClass)
      case None =>
        project.platform.userMainClass match {
          case Some(configMainClass) => Right(configMainClass)
          case None =>
            Tasks.findMainClasses(state, project) match {
              case Nil =>
                val msg = Feedback.missingMainClass(project)
                Left(state.withError(msg, ExitStatus.InvalidCommandLineOption))
              case List(mainClass) => Right(mainClass)
              case mainClasses =>
                val msg = Feedback
                  .expectedDefaultMainClass(project)
                  .suggest(Feedback.listMainClasses(mainClasses))
                Left(state.withError(msg, ExitStatus.InvalidCommandLineOption))
            }

        }
    }
  }
}
