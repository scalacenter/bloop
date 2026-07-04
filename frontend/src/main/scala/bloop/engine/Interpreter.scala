package bloop.engine

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.collection.immutable.Nil
import scala.concurrent.Promise

import bloop.Compiler
import bloop.DependencyResolution
import bloop.ScalaInstance
import bloop.bsp.BspServer
import bloop.cli.Commands.CompilingCommand
import bloop.cli.Validate
import bloop.cli._
import bloop.cli.completion.Case
import bloop.cli.completion.Mode
import bloop.data.Platform
import bloop.data.Project
import bloop.engine.Feedback.XMessageString
import bloop.engine.tasks.CompileTask
import bloop.engine.tasks.LinkTask
import bloop.engine.tasks.RunMode
import bloop.engine.tasks.Tasks
import bloop.engine.tasks.TestTask
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.engine.tasks.toolchains.ScalaNativeToolchain
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.io.RelativePath
import bloop.io.SourceWatcher
import bloop.logging.DebugFilter
import bloop.logging.Logger
import bloop.logging.NoopLogger
import bloop.reporter.LogReporter
import bloop.reporter.ReporterInputs
import bloop.task.Task
import bloop.testing.LoggingEventHandler
import bloop.testing.TestInternals

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
          case Run(cmd: Commands.About, _) =>
            notHandled("about", cmd.cliOptions, state)
          case Run(cmd: Commands.Version, _) =>
            notHandled("version", cmd.cliOptions, state)
          case Run(cmd: Commands.Help, _) =>
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
    val reachable = Dag.dfs(getProjectsDag(projects, state), mode = Dag.PreOrder)
    val projectsSourcesAndDirs = reachable.map { project =>
      for {
        unmanaged <- project.allUnmanagedSourceFilesAndDirectories
        // Globs expand to the files existing now; watch their directories so
        // that creating a new matching file also triggers an iteration
        globDirectories = project.sourcesGlobs.map(_.directory)
        generatorSourceDirs = project.sourceGenerators.flatMap(gen =>
          gen.sourcesGlobs.map(_.directory) ++ gen.unmangedInputs
        )
      } yield unmanaged ++ globDirectories ++ generatorSourceDirs
    }
    val groupTasks =
      projectsSourcesAndDirs.grouped(8).map(group => Task.gatherUnordered(group)).toList
    val watchedPlainSources = reachable.flatMap(_.sources.map(_.underlying))
    val watchedProjectGlobs = reachable.flatMap(_.sourcesGlobs)
    val watchedSourceGeneratorGlobs = reachable.flatMap { project =>
      project.sourceGenerators.flatMap(_.sourcesGlobs)
    }
    Task
      .sequence(groupTasks)
      .map(fp => fp.flatten.flatten.map(_.underlying))
      .flatMap { allSources =>
        val watcher = SourceWatcher(
          projects.map(_.name),
          allSources,
          watchedPlainSources,
          watchedProjectGlobs,
          watchedSourceGeneratorGlobs,
          state.logger
        )
        val fg = (state: State) => {
          val newState = State.stateCache.getUpdatedStateFrom(state).getOrElse(state)
          f(newState).map { state =>
            watcher.notifyWatch()
            State.stateCache.updateBuild(state)
          }
        }

        if (!bloop.util.CrossPlatform.isWindows)
          state.logger.info(bloop.util.Console.clearCommand)

        // Force the first execution before relying on the file watching task
        fg(state).flatMap(newState => watcher.watch(newState, fg))

      }
  }

  private def runCompile(
      cmd: CompilingCommand,
      state0: State,
      projects: List[Project],
      noColor: Boolean,
      printSummary: Boolean = false
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
        bestEffortAllowed = false,
        Promise[Unit](),
        CompileClientStore.NoStore,
        state.logger
      )
    }

    Task(System.nanoTime).flatMap { startNanos =>
      compileTask.map { compiled =>
        val newState = compiled.mergeStatus(ExitStatus.Ok)
        if (printSummary) {
          val dagProjects = Dag.dfs(getProjectsDag(projects, newState), mode = Dag.PreOrder)
          printCompileSummary(dagProjects, newState, (System.nanoTime - startNanos) / 1000000)
        }
        newState
      }
    }
  }

  /**
   * Prints a summary of the compile run over the closure of the requested projects:
   * per-project compile durations sorted slowest-first, blocked and errored projects without
   * timing, the sum of all module compile times and the wall-clock duration of the whole run,
   * including the clean preceding a non-incremental compile.
   */
  private def printCompileSummary(
      dagProjects: List[Project],
      newState: State,
      wallClockMs: Long
  ): Unit = {
    val results = dagProjects.map(p => (p, newState.results.latestResult(p)))
    val timed = results.collect {
      case (p, Compiler.Result.Success(_, _, elapsed, _, _, _, _)) => (p.name, elapsed, "")
      case (p, Compiler.Result.Failed(_, _, elapsed, _, _)) => (p.name, elapsed, " (failed)")
      case (p, Compiler.Result.Cancelled(_, elapsed, _)) => (p.name, elapsed, " (cancelled)")
    }
    val untimed = results.collect {
      case (p, Compiler.Result.Blocked(on)) => s"${p.name} - blocked on ${on.mkString(", ")}"
      case (p, _: Compiler.Result.GlobalError) => s"${p.name} - failed with a global error"
    }

    val logger = newState.logger
    val delimiter = "=" * LoggingEventHandler.getTerminalWidth
    logger.info(delimiter)
    timed.sortBy { case (name, elapsed, _) => (-elapsed, name) }.foreach {
      case (name, elapsed, status) => logger.info(s"$name - ${elapsed}ms$status")
    }
    untimed.sorted.foreach(logger.info)
    logger.info(s"Total module compile time: ${timed.map(_._2).sum}ms")
    logger.info(s"Wall-clock duration: ${wallClockMs}ms")
    logger.info(delimiter)
  }

  private def compile(cmd: Commands.Compile, state: State): Task[State] = {
    def runCompileProjects(projectsToCompile: List[Project]) = {
      val projects: List[Project] = {
        if (!cmd.cascade) projectsToCompile
        else Dag.inverseDependencies(state.build.dags, projectsToCompile).reduced
      }

      if (!cmd.watch) runCompile(cmd, state, projects, cmd.cliOptions.noColor, cmd.summary)
      else watch(projects, state)(runCompile(cmd, _, projects, cmd.cliOptions.noColor, cmd.summary))
    }

    if (cmd.projects.isEmpty) {
      // If no projects specified, compile all projects
      runCompileProjects(state.build.loadedProjects.map(_.project))
    } else {
      val lookup = lookupProjects(cmd.projects, state.build.getProjectFor(_))
      if (lookup.missing.nonEmpty) Task.now(reportMissing(lookup.missing, state))
      else runCompileProjects(lookup.found)
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
      state.build.loadedProjects.map(_.project.name).sorted.foreach(logger.info)
    }

    state.mergeStatus(ExitStatus.Ok)
  }

  private def compileAnd[C <: CompilingCommand](
      cmd: C,
      state: State,
      projects: List[Project],
      noColor: Boolean,
      nextAction: String
  )(next: State => Task[State]): Task[State] = {
    runCompile(cmd, state, projects, noColor).flatMap { compiled =>
      if (compiled.status != ExitStatus.CompilationError) next(compiled)
      else {
        val projectsString = projects.mkString(", ")
        Task.now(
          compiled.withDebug(s"Failed compilation for $projectsString. Skipping $nextAction...")(
            DebugFilter.Compilation
          )
        )
      }
    }
  }

  /**
   * Picks the REPL artifact and main class for a Scala version. Scala 2 uses `scala-compiler`
   * with `MainGenericRunner`; Scala 3 uses `dotty.tools.repl.Main`, served by `scala3-compiler`
   * before 3.8.0 and the dedicated `scala3-repl` artifact from 3.8.0 on.
   */
  private[bloop] def scalaReplArtifactAndMain(
      scalaVersion: String
  ): (List[DependencyResolution.Artifact], String) = {
    def artifact(module: String) =
      DependencyResolution.Artifact("org.scala-lang", module, scalaVersion)
    if (scalaVersion.startsWith("3.")) {
      // The REPL moved to `scala3-repl` at 3.8.0; below that it ships in `scala3-compiler`.
      // `scala3-repl` excludes the stdlib, so `scala3-compiler` must be resolved alongside it.
      val artifacts =
        if (isScalaVersionAtLeast(scalaVersion, 3, 8))
          List(artifact("scala3-repl_3"), artifact("scala3-compiler_3"))
        else List(artifact("scala3-compiler_3"))
      (artifacts, "dotty.tools.repl.Main")
    } else {
      (List(artifact("scala-compiler")), "scala.tools.nsc.MainGenericRunner")
    }
  }

  /** True if `scalaVersion` >= `major.minor`, comparing numeric parts and ignoring any suffix. */
  private[bloop] def isScalaVersionAtLeast(
      scalaVersion: String,
      major: Int,
      minor: Int
  ): Boolean = {
    def num(s: String): Option[Int] = scala.util.Try(s.toInt).toOption
    val parts = scalaVersion.split("-").head.split('.')
    (parts.lift(0).flatMap(num), parts.lift(1).flatMap(num)) match {
      case (Some(maj), Some(min)) => maj > major || (maj == major && min >= minor)
      case _ => false
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
            // `--exclude-root` starts the console against only the target's dependencies, so we
            // compile (and later classpath) those instead of the target project itself.
            val projectsToCompile =
              if (cmd.excludeRoot) project.dependencies.flatMap(state.build.getProjectFor)
              else List(project)
            compileAnd(
              cmd,
              state,
              projectsToCompile,
              cmd.cliOptions.noColor,
              "`console`"
            ) { state =>
              // Helper to output a REPL command - either print it or write to file
              def outputReplCommand(
                  replCmd: List[String],
                  replName: String
              ): Task[State] = {
                val cmdString = replCmd.mkString("\n")
                cmd.outFile match {
                  case None =>
                    // Print the command for the client to run locally (interactive REPLs
                    // need direct terminal access which isn't available through nailgun)
                    Task.now(state.withInfo(cmdString))
                  case Some(outFile) =>
                    try {
                      Files.write(outFile, cmdString.getBytes(StandardCharsets.UTF_8))
                      val msg = s"Wrote $replName command to $outFile"
                      Task.now(state.withDebug(msg)(DebugFilter.All))
                    } catch {
                      case _: IOException =>
                        val msg = s"Unexpected error when writing $replName command to $outFile"
                        Task.now(state.withError(msg, ExitStatus.RunError))
                    }
                }
              }

              // Resolve REPL artifacts programmatically (via coursier's API) and emit a plain
              // `java -cp ... <main>` command. This avoids depending on a `coursier`/`cs`
              // launcher on the client's PATH and surfaces resolution failures with a clear
              // message instead of a raw stack trace.
              def withResolved(artifacts: List[DependencyResolution.Artifact], hint: String)(
                  build: List[AbsolutePath] => Task[State]
              ): Task[State] = {
                DependencyResolution.resolveWithErrors(artifacts, state.logger) match {
                  case Right(jars) => build(jars.toList)
                  case Left(error) =>
                    val coords =
                      artifacts
                        .map(a => s"${a.organization}:${a.module}:${a.version}")
                        .mkString(", ")
                    val msg = s"Could not resolve $coords. $hint ${error.getMessage}"
                    Task.now(state.withError(msg, ExitStatus.RunError))
                }
              }

              val dag = state.build.getDagFor(project)
              // Launch under the project's configured JDK and JVM options (mirrors run/test via
              // JvmProcessForker), not an ambient `java` — this is a JDK-sensitive feature.
              val jdkConfig = project.runtimeJdkConfig
              val javaBin = jdkConfig.map(_.javaBinary.syntax).getOrElse("java")
              val javaOptions = jdkConfig.toList.flatMap(_.javaOptions.toList)
              // With `--exclude-root` the REPL classpath is the dependencies' classpath; otherwise
              // it's the target's full runtime classpath (which includes its own classes).
              val replClasspath =
                if (cmd.excludeRoot)
                  projectsToCompile
                    .flatMap(p => p.fullRuntimeClasspath(state.build.getDagFor(p), state.client))
                    .distinct
                else project.fullRuntimeClasspath(dag, state.client).toList

              // Report a missing configured JDK here (like run does via JvmProcessForker) instead
              // of emitting a command that only fails later in the client.
              jdkConfig.map(_.javaBinary).filter(!_.exists) match {
                case Some(missing) =>
                  val msg = s"Configured Java executable does not exist: ${missing.syntax}"
                  Task.now(state.withError(msg, ExitStatus.RunError))
                case None =>
                  cmd.repl match {
                    case ScalacRepl =>
                      if (cmd.ammoniteVersion.isDefined) {
                        val errMsg =
                          "Specifying an Ammonite version while using the Scalac console does not work"
                        Task.now(state.withError(errMsg, ExitStatus.InvalidCommandLineOption))
                      } else {
                        val scalaVersion = project.scalaInstance
                          .map(_.version)
                          .orElse(
                            ScalaInstance.scalaInstanceForJavaProjects(state.logger).map(_.version)
                          )
                          .getOrElse(BuildInfo.scalaVersion)

                        val (artifacts, mainClass) = scalaReplArtifactAndMain(scalaVersion)
                        withResolved(
                          artifacts,
                          s"The Scala REPL may be unavailable for $scalaVersion."
                        ) { replJars =>
                          // REPL jars run the JVM (`usejavacp` lets the compiler find the JDK classes
                          // on JDK > 8); the project is the REPL's user classpath.
                          val replCp = replJars.map(_.syntax).mkString(java.io.File.pathSeparator)
                          val projectCp =
                            replClasspath.map(_.syntax).mkString(java.io.File.pathSeparator)
                          val scalaCmd =
                            (javaBin :: javaOptions) ++ List(
                              "-Dscala.usejavacp=true",
                              "-cp",
                              replCp,
                              mainClass,
                              "-classpath",
                              projectCp
                            ) ++ cmd.args
                          outputReplCommand(scalaCmd, "Scala REPL")
                        }
                      }
                    case AmmoniteRepl =>
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

                      val scalaVersion = findScalaVersion(dag)
                        .orElse(
                          ScalaInstance.scalaInstanceForJavaProjects(state.logger).map(_.version)
                        )
                        .getOrElse(BuildInfo.scalaVersion)

                      val ammVersion = cmd.ammoniteVersion.getOrElse("latest.release")
                      val artifact =
                        DependencyResolution.Artifact(
                          "com.lihaoyi",
                          s"ammonite_$scalaVersion",
                          ammVersion
                        )
                      val hint = s"Ammonite may not be published for Scala $scalaVersion; " +
                        "try a different --ammonite-version."
                      withResolved(List(artifact), hint) { ammJars =>
                        // Ammonite seeds the REPL from its own classpath, so the project goes there too.
                        val classpath =
                          (ammJars ++ replClasspath)
                            .map(_.syntax)
                            .mkString(java.io.File.pathSeparator)
                        val ammCmd =
                          (javaBin :: javaOptions) ++ List(
                            "-cp",
                            classpath,
                            "ammonite.Main"
                          ) ++ cmd.args
                        outputReplCommand(ammCmd, "Ammonite")
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

  /**
   * A fixed `--jvm-debug` port can only back one forked JVM, so combining it with `--parallel`
   * conflicts only when more than one JVM test project will be forked at once. Non-JVM (JS, Native)
   * test projects don't fork a debuggable JVM, so they never collide on the port.
   */
  private[bloop] def debugForkCollision(
      jvmDebug: Option[Int],
      parallel: Boolean,
      projectsToTest: List[Project]
  ): Boolean =
    jvmDebug.isDefined && parallel &&
      projectsToTest.count(p =>
        TestTask.isTestProject(p) && p.platform.isInstanceOf[Platform.Jvm]
      ) > 1

  private[bloop] def warnIfJvmDebugUnsupported(
      project: Project,
      jvmDebug: Option[Int],
      logger: Logger
  ): Unit = {
    if (jvmDebug.isDefined && !project.platform.isInstanceOf[Platform.Jvm])
      logger.warn(
        s"Ignoring --jvm-debug for '${project.name}': it is only supported for JVM projects."
      )
  }

  private def test(cmd: Commands.Test, state: State): Task[State] = {
    import state.logger

    val runMode = cmd.jvmDebug match {
      case Some(port) => RunMode.Debug(Some(port), suspend = cmd.jvmDebugSuspend)
      case None => RunMode.Normal
    }

    def testAllProjects(
        state: State,
        projectsToCompile: List[Project],
        projectsToTest: List[Project]
    ): Task[State] = {
      if (debugForkCollision(cmd.jvmDebug, cmd.parallel, projectsToTest)) {
        Task.now(
          state.withError(Feedback.jvmDebugWithParallel, ExitStatus.InvalidCommandLineOption)
        )
      } else {
        projectsToTest.foreach(warnIfJvmDebugUnsupported(_, cmd.jvmDebug, logger))
        val testFilter = TestInternals.parseFilters(cmd.only)
        compileAnd(cmd, state, projectsToCompile, cmd.cliOptions.noColor, "`test`") { state =>
          logger.debug(
            s"Preparing test execution for ${projectsToTest.mkString(", ")}"
          )(DebugFilter.Test)

          val handler = new LoggingEventHandler(state.logger)

          Tasks
            .test(
              state,
              projectsToTest,
              cmd.args,
              testFilter,
              ch.epfl.scala.bsp.ScalaTestSuites(Nil, Nil, Nil),
              handler,
              cmd.parallel,
              runMode
            )
            .map(testRuns => state.mergeStatus(testRuns.status))
        }
      }
    }

    def testSelectedProjects(userSelectedProjects: List[Project]): Task[State] = {
      // Projects to test != projects that need compiling
      val (projectsToCompile, projectsToTest) = {
        if (!cmd.cascade) {
          val projectsToTest = {
            if (!cmd.includeDependencies) userSelectedProjects
            else
              userSelectedProjects.flatMap(p =>
                Dag.dfs(state.build.getDagFor(p), mode = Dag.PreOrder)
              )
          }

          (userSelectedProjects, projectsToTest)
        } else {
          val result = Dag.inverseDependencies(state.build.dags, userSelectedProjects)
          val cascaded = result.allCascaded
          val projectsToTest =
            if (!cmd.includeDependencies) cascaded
            else {
              val dependencies = userSelectedProjects.flatMap { p =>
                Dag.dfs(state.build.getDagFor(p), mode = Dag.PreOrder)
              }
              (cascaded ++ dependencies).distinct
            }
          (result.reduced, projectsToTest)
        }
      }

      logger.debug(
        s"Preparing compilation of ${projectsToCompile.mkString(", ")} transitively"
      )(DebugFilter.Test)

      if (!cmd.watch) testAllProjects(state, projectsToCompile, projectsToTest)
      else watch(projectsToCompile, state)(testAllProjects(_, projectsToCompile, projectsToTest))
    }

    if (cmd.projects.isEmpty) {
      // If no projects specified, test all projects (using pickTestProject logic for each)
      testSelectedProjects(
        state.build.loadedProjects.flatMap(lp => Tasks.pickTestProject(lp.project.name, state))
      )
    } else {
      val lookup = lookupProjects(cmd.projects, Tasks.pickTestProject(_, state))
      if (lookup.missing.nonEmpty) Task.now(reportMissing(lookup.missing, state))
      else testSelectedProjects(lookup.found)
    }
  }

  private case class ProjectLookup(found: List[Project], missing: List[String])
  private def lookupProjects(
      names: List[String],
      lookup: String => Option[Project]
  ): ProjectLookup = {
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
          val commandsAcceptingProjects = Commands.RawCommand.help.messages.collect {
            case (name, help) if help.args.exists(_.name.name == "projects") => name.mkString(" ")
          }

          state.withInfo(commandsAcceptingProjects.mkString(" "))
        }
      case Mode.Commands =>
        Task {
          for {
            (name, args) <- Commands.RawCommand.help.messages
            completion <- cmd.format.showCommand(name.mkString(" "), args)
          } state.logger.info(completion)
          state
        }
      case Mode.Projects =>
        Task {
          for {
            loadedProject <- state.build.loadedProjects
            project = loadedProject.project
            completion <- cmd.format.showProject(project)
          } state.logger.info(completion)
          state
        }
      case Mode.Flags =>
        Task {
          for {
            command <- cmd.command
            message <- Commands.RawCommand.help.messages.toMap.get(Seq(command))
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
          TestTask.findTestNamesWithFramework(project, stateWithNoopLogger).map { discovered =>
            for {
              classesWithFramework <- discovered
              testFqcn <- classesWithFramework.classes
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
    def doClean(projects: List[Project]): Task[State] = {
      val cascaded =
        if (!cmd.cascade) Nil
        else Dag.inverseDependencies(state.build.dags, projects).allCascaded
      val downward =
        if (!cmd.includeDependencies) Nil
        else projects.flatMap(p => Dag.dfs(state.build.getDagFor(p), mode = Dag.PreOrder))
      val targets = (projects ++ cascaded ++ downward).distinct
      Tasks.clean(state, targets, includeDeps = false).map(_.mergeStatus(ExitStatus.Ok))
    }

    if (cmd.projects.isEmpty) doClean(state.build.loadedProjects.map(_.project))
    else {
      val lookup = lookupProjects(cmd.projects, state.build.getProjectFor(_))
      if (lookup.missing.nonEmpty) Task.now(reportMissing(lookup.missing, state))
      else doClean(lookup.found)
    }
  }

  private[bloop] def link(cmd: Commands.Link, state: State): Task[State] = {
    def doLink(project: Project)(state: State): Task[State] = {
      compileAnd(cmd, state, List(project), cmd.cliOptions.noColor, "`link`") { state =>
        getMainClass(state, project, cmd.main) match {
          case Left(state) =>
            project.platform match {
              case platform @ Platform.Js(config, _, _) =>
                val targetDirectory = ScalaJsToolchain.linkTargetFrom(project, config)
                LinkTask.linkJS(cmd, project, state, false, None, targetDirectory, platform, None)
              case platform @ Platform.Native(config, _, _) =>
                val target = ScalaNativeToolchain.linkTargetFrom(project, config)
                LinkTask.linkNative(cmd, project, state, None, target, platform, None)
              case _ => Task.now(state)
            }

          case Right(mainClass) =>
            project.platform match {
              case platform @ Platform.Native(config, _, _) =>
                val target = ScalaNativeToolchain.linkTargetFrom(project, config)
                LinkTask.linkNative(cmd, project, state, Some(mainClass), target, platform, None)

              case platform @ Platform.Js(config, _, _) =>
                val targetDirectory = ScalaJsToolchain.linkTargetFrom(project, config)
                LinkTask.linkJS(
                  cmd,
                  project,
                  state,
                  false,
                  Some(mainClass),
                  targetDirectory,
                  platform,
                  None
                )

              case _: Platform.Jvm =>
                val msg = Feedback.noLinkFor(project)
                Task.now(state.withError(msg, ExitStatus.InvalidCommandLineOption))
            }
        }
      }
    }

    val lookup = lookupProjects(cmd.projects, state.build.getProjectFor(_))
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
    val runMode = cmd.jvmDebug match {
      case Some(port) => RunMode.Debug(Some(port), suspend = cmd.jvmDebugSuspend)
      case None => RunMode.Normal
    }
    def doRun(project: Project)(state: State): Task[State] = {
      val cwd = project.workingDirectory
      compileAnd(cmd, state, List(project), cmd.cliOptions.noColor, "`run`") { state =>
        getMainClass(state, project, cmd.main) match {
          case Left(failedState) => Task.now(failedState)
          case Right(mainClass) =>
            warnIfJvmDebugUnsupported(project, cmd.jvmDebug, state.logger)
            project.platform match {
              case platform @ Platform.Native(config, _, _) =>
                val target = ScalaNativeToolchain.linkTargetFrom(project, config)
                LinkTask
                  .linkNative(cmd, project, state, Some(mainClass), target, platform, None)
                  .flatMap { state =>
                    val args = (target.syntax +: cmd.args).toArray
                    if (!state.status.isOk) Task.now(state)
                    else Tasks.runNativeOrJs(state, cwd, args)
                  }
              case platform @ Platform.Js(config, _, _) =>
                val targetDirectory = ScalaJsToolchain.linkTargetFrom(project, config)
                LinkTask
                  .linkJS(
                    cmd,
                    project,
                    state,
                    false,
                    Some(mainClass),
                    targetDirectory,
                    platform,
                    None
                  )
                  .flatMap { state =>
                    // TODO: We should use `jsEnvInput` to obtain the JS files to run
                    // (or even use https://github.com/scala-js/scala-js-js-envs to run ideally),
                    // https://github.com/scala-js/scala-js/blob/6a145af4dc575340a40b80459d1bf15184c3a2da/sbt-plugin/src/main/scala/org/scalajs/sbtplugin/ScalaJSPlugin.scala#L252-L255
                    // and get command line options from `jsEnv` tasks for Node.js options.
                    // https://github.com/scala-js/scala-js/blob/6a145af4dc575340a40b80459d1bf15184c3a2da/sbt-plugin/src/main/scala/org/scalajs/sbtplugin/ScalaJSPlugin.scala#L238-L240
                    // For now, we just filter .js files (so we don't include sourcemap, .wasm files, and __loader.js generated for Wasm), and run them.
                    val files = targetDirectory.list
                      .map(_.toString())
                      .filter(name => name.endsWith(".js") && !name.endsWith("__loader.js"))
                    // We use node to run the program (is this a special case?)
                    val args = ("node" +: files ::: cmd.args).toArray
                    if (!state.status.isOk) Task.now(state)
                    else Tasks.runNativeOrJs(state, cwd, args)
                  }
              case jvm: Platform.Jvm =>
                val javaEnv = project.runtimeJdkConfig.getOrElse(jvm.config)
                Tasks.runJVM(
                  state,
                  project,
                  javaEnv,
                  cwd,
                  mainClass,
                  cmd.args.toArray,
                  cmd.skipJargs,
                  envVars = Nil,
                  runMode
                )
            }
        }
      }
    }

    val lookup = lookupProjects(cmd.projects, state.build.getProjectFor(_))
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
