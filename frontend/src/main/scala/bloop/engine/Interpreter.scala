package bloop.engine

import bloop.CompileMode
import bloop.bsp.BspServer
import bloop.cli._
import bloop.cli.CliParsers.CommandsMessages
import bloop.cli.completion.{Case, Mode}
import bloop.io.{AbsolutePath, RelativePath, SourceWatcher}
import bloop.logging.{DebugFilter, Logger, NoopLogger}
import bloop.testing.{LoggingEventHandler, TestInternals}
import bloop.engine.tasks.{CompileTask, LinkTask, RunMode, Tasks, TestTask}
import bloop.cli.Commands.CompilingCommand
import bloop.cli.Validate
import bloop.util.JavaRuntime
import bloop.data.{ClientInfo, JdkConfig, Platform, Project}
import bloop.engine.Feedback.XMessageString
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import bloop.reporter.{LogReporter, ReporterInputs}
import caseapp.core.CommandMessages
import monix.eval.Task

import scala.concurrent.Promise
import java.nio.file.Files
import java.nio.charset.StandardCharsets

import bloop.ScalaInstance

import scala.collection.immutable.Nil
import scala.annotation.tailrec
import java.io.IOException

import bloop.engine.Dag.DagResult

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
          case Run(cmd: Commands.About, next) =>
            notHandled("about", cmd.cliOptions, state)
          case Run(cmd: Commands.Help, next) =>
            notHandled("help", cmd.cliOptions, state)
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

  private def notHandled(command: String, cliOptions: CliOptions, state: State): Task[State] = {
    val msg = s"The handling of `$command` does not happen in the `Interpreter`"
    val printAction =
      Print(msg, cliOptions.common, Exit(ExitStatus.UnexpectedError))
    execute(printAction, Task.now(state))
  }

  private final val t = "    "
  private final val line = System.lineSeparator()

  private def getProjectsDag(projects: List[Project], state: State): Dag[Project] =
    Aggregate(projects.map(p => state.build.getDagFor(p)))

  private def runBsp(cmd: Commands.ValidatedBsp, state: State): Task[State] = {
    import ExecutionContext.{ioScheduler, scheduler}
    BspServer
      .run(cmd, state, RelativePath(".bloop"), None, None, scheduler, ioScheduler)
      .executeOn(ioScheduler)
  }

  private[bloop] def watch(projects: List[Project], state: State)(
      f: State => Task[State]
  ): Task[State] = {
    val reachable = Dag.dfs(getProjectsDag(projects, state))
    val projectsSourcesAndDirs = reachable.map(_.allSourceFilesAndDirectories)
    val groupTasks =
      projectsSourcesAndDirs.grouped(8).map(group => Task.gatherUnordered(group)).toList
    Task
      .sequence(groupTasks)
      .map(fp => fp.flatten.flatten.map(_.underlying))
      .flatMap { allSources =>
        val watcher = SourceWatcher(projects.map(_.name), allSources, state.logger)
        val fg = (state: State) => {
          val newState = State.stateCache.getUpdatedStateFrom(state).getOrElse(state)
          f(newState).map { state =>
            watcher.notifyWatch()
            State.stateCache.updateBuild(state)
          }
        }

        if (!bloop.util.CrossPlatform.isWindows)
          state.logger.info("\u001b[H\u001b[2J")

        // Force the first execution before relying on the file watching task
        fg(state).flatMap(newState => watcher.watch(newState, fg))

      }
  }

  private def runCompile(
      cmd: CompilingCommand,
      state0: State,
      projects: List[Project],
      excludeRoot: Boolean = false,
      noColor: Boolean
  ): Task[State] = {
    // Make new state cleaned of all compilation products if compilation is not incremental
    val state: Task[State] = {
      if (cmd.incremental) Task.now(state0)
      else {
        val projects = state0.build.loadedProjects.map(_.project)
        Tasks.clean(state0, projects, true)
      }
    }

    val compileTask = state.flatMap { state =>
      val config = ReporterKind.toReporterConfig(cmd.reporter).copy(colors = !noColor)
      val dag = getProjectsDag(projects, state)
      val createReporter = (inputs: ReporterInputs[Logger]) =>
        new LogReporter(inputs.project, inputs.logger, inputs.cwd, config)
      import bloop.engine.tasks.compilation.CompileClientStore
      CompileTask.compile(
        state,
        dag,
        createReporter,
        cmd.pipeline,
        excludeRoot,
        Promise[Unit](),
        CompileClientStore.NoStore,
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

      if (!cmd.watch) runCompile(cmd, state, projects, false, cmd.cliOptions.noColor)
      else watch(projects, state)(runCompile(cmd, _, projects, false, cmd.cliOptions.noColor))
    }
  }

  private def showProjects(cmd: Commands.Projects, state: State): Task[State] = Task {
    import state.logger
    if (cmd.dotGraph) {
      val stringToMainProjects =
        state.build.mainOnlyProjects.map(lp => lp.project.name -> lp.project).toMap
      val DagResult(dags, _, _) = Dag.fromMap(stringToMainProjects)
      val contents = Dag.toDotGraph(dags)
      logger.info(contents)
    } else {
      val configDirectory = state.build.origin.syntax
      logger.debug(s"Projects loaded from '$configDirectory':")(DebugFilter.All)
      state.build.mainOnlyProjects.map(_.project.name).sorted.foreach(logger.info)
    }

    state.mergeStatus(ExitStatus.Ok)
  }

  private def compileAnd[C <: CompilingCommand](
      cmd: C,
      state: State,
      projects: List[Project],
      excludeRoot: Boolean,
      noColor: Boolean,
      nextAction: String
  )(next: State => Task[State]): Task[State] = {
    runCompile(cmd, state, projects, excludeRoot, noColor).flatMap { compiled =>
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
            compileAnd(
              cmd,
              state,
              List(project),
              cmd.excludeRoot,
              cmd.cliOptions.noColor,
              "`console`"
            ) { state =>
              cmd.repl match {
                case ScalacRepl =>
                  if (cmd.ammoniteVersion.isDefined) {
                    val errMsg =
                      "Specifying an Ammonite version while using the Scalac console does not work"
                    Task.now(state.withError(errMsg, ExitStatus.InvalidCommandLineOption))
                  } else if (cmd.args.nonEmpty) {
                    val errMsg = "Passing arguments to the Scalac console does not work"
                    Task.now(state.withError(errMsg, ExitStatus.InvalidCommandLineOption))
                  } else {
                    Tasks.console(state, project, cmd.excludeRoot)
                  }
                case AmmoniteRepl =>
                  // Look for version of scala instance in any of the projects
                  def findScalaVersion(dag: Dag[Project]): Option[String] = {
                    dag match {
                      case Aggregate(dags) => dags.flatMap(findScalaVersion).headOption
                      case Leaf(value) => value.scalaInstance.map(_.version)
                      case Parent(value, children) =>
                        children.flatMap(findScalaVersion) match {
                          case Nil => value.scalaInstance.map(_.version)
                          case xs => Some(xs.head)
                        }
                    }
                  }

                  import bloop.engine.ExecutionContext.ioScheduler
                  val dag = state.build.getDagFor(project)
                  // If none version is found (e.g. all Java projects), use Bloop's scala version
                  val scalaVersion = findScalaVersion(dag)
                    .getOrElse(ScalaInstance.scalaInstanceForJavaProjects(state.logger))

                  val ammVersion = cmd.ammoniteVersion.getOrElse("latest.release")
                  val coursierCmd = List(
                    "coursier",
                    "launch",
                    s"com.lihaoyi:ammonite_$scalaVersion:$ammVersion",
                    "--main-class",
                    "ammonite.Main",
                    "--scala-version",
                    scalaVersion
                  )

                  val classpath = project.fullRuntimeClasspath(dag, state.client)
                  val coursierClasspathArgs =
                    classpath.flatMap(elem => Seq("--extra-jars", elem.syntax))

                  val ammArgs = "--" :: cmd.args

                  val ammoniteCmd = (coursierCmd ++ coursierClasspathArgs ++ ammArgs).mkString(" ")
                  cmd.outFile match {
                    case None => Task.now(state.withInfo(ammoniteCmd))
                    case Some(outFile) =>
                      try {
                        Files.write(outFile, ammoniteCmd.getBytes(StandardCharsets.UTF_8))
                        val msg = s"Wrote Ammonite command to $outFile"
                        Task.now(state.withDebug(msg)(DebugFilter.All))
                      } catch {
                        case _: IOException =>
                          val msg = s"Unexpected error when writing Ammonite command to $outFile"
                          Task.now(state.withError(msg, ExitStatus.RunError))
                      }
                  }
              }
            }
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
      val userSelectedProjects = lookup.found
      val (projectsToCompile, projectsToTest) = {
        if (!cmd.cascade) {
          val projectsToTest = {
            if (!cmd.includeDependencies) userSelectedProjects
            else userSelectedProjects.flatMap(p => Dag.dfs(state.build.getDagFor(p)))
          }

          (userSelectedProjects, projectsToTest)
        } else {
          val result = Dag.inverseDependencies(state.build.dags, userSelectedProjects)
          (result.reduced, result.allCascaded)
        }
      }

      logger.debug(
        s"Preparing compilation of ${projectsToCompile.mkString(", ")} transitively"
      )(DebugFilter.Test)

      def testAllProjects(state: State): Task[State] = {
        val testFilter = TestInternals.parseFilters(cmd.only)
        compileAnd(cmd, state, projectsToCompile, false, cmd.cliOptions.noColor, "`test`") {
          state =>
            logger.debug(
              s"Preparing test execution for ${projectsToTest.mkString(", ")}"
            )(DebugFilter.Test)

            val handler = new LoggingEventHandler(state.logger)

            Tasks.test(
              state,
              projectsToTest,
              cmd.args,
              testFilter,
              handler,
              cmd.parallel,
              RunMode.Normal
            )
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
            loadedProject <- state.build.mainOnlyProjects
            project = loadedProject.project
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
            stateWithNoopLogger = state.copy(logger = NoopLogger)
            project <- state.build.getProjectFor(projectName)
            main <- Tasks.findMainClasses(stateWithNoopLogger, project)
            completion <- cmd.format.showMainName(main)
          } state.logger.info(completion)
          state
        }
      case Mode.TestsFQCN =>
        val printTestTask = for {
          projectName <- cmd.project
          stateWithNoopLogger = state.copy(logger = NoopLogger)
          project <- Tasks.pickTestProject(projectName, stateWithNoopLogger)
        } yield {
          TestTask.findFullyQualifiedTestNames(project, stateWithNoopLogger).map { testsFqcn =>
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
      val projects = state.build.loadedProjects.map(_.project)
      Tasks.clean(state, projects, cmd.includeDependencies).map(_.mergeStatus(ExitStatus.Ok))
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
      compileAnd(cmd, state, List(project), false, cmd.cliOptions.noColor, "`link`") { state =>
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

              case _: Platform.Jvm =>
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
      val cwd = project.workingDirectory
      compileAnd(cmd, state, List(project), false, cmd.cliOptions.noColor, "`run`") { state =>
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
              case Platform.Jvm(javaEnv, _, _, _, _) =>
                Tasks.runJVM(
                  state,
                  project,
                  javaEnv,
                  cwd,
                  mainClass,
                  cmd.args.toArray,
                  cmd.skipJargs,
                  RunMode.Normal
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
