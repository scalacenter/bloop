package bloop.bsp

import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import bloop.{CompileMode, Compiler, ScalaInstance}
import bloop.cli.{Commands, ExitStatus, Validate}
import bloop.data.{Platform, Project}
import bloop.engine.tasks.{CompilationTask, Tasks}
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import bloop.engine._
import bloop.internal.build.BuildInfo
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspServerLogger, DebugFilter}
import bloop.reporter.{BspProjectReporter, ReporterConfig}
import bloop.testing.{BspLoggingEventHandler, TestInternals}
import monix.eval.Task
import ch.epfl.scala.bsp.{
  BuildTargetIdentifier,
  MessageType,
  ShowMessageParams,
  WorkspaceBuildTargets,
  endpoints
}

import scala.meta.jsonrpc.{JsonRpcClient, Response => JsonRpcResponse, Services => JsonRpcServices}
import xsbti.Problem
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.ScalaBuildTarget.encodeScalaBuildTarget
import monix.execution.atomic.AtomicInt

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration.FiniteDuration

final class BloopBspServices(
    callSiteState: State,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath,
    socketInput: InputStream,
    exitStatus: AtomicInt
) {
  private implicit val debugFilter: DebugFilter = DebugFilter.Bsp
  private type ProtocolError = JsonRpcResponse.Error
  private type BspResponse[T] = Either[ProtocolError, T]

  /** The return type of every endpoint implementation */
  private type BspEndpointResponse[T] = Task[BspResponse[T]]

  /** The return type of intermediate BSP compputations */
  private type BspResult[T] = Task[(State, BspResponse[T])]

  /** The return type of a bsp computation wrapped by `ifInitialized` */
  private type BspComputation[T] = State => BspResult[T]

  // Disable ansi codes for now so that the BSP clients don't get unescaped color codes
  private val taskIdCounter: AtomicInt = AtomicInt(0)
  private val bspLogger = BspServerLogger(callSiteState, client, taskIdCounter, false)
  final val services = JsonRpcServices
    .empty(bspLogger)
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .request(endpoints.Build.shutdown)(shutdown(_))
    .notificationAsync(endpoints.Build.exit)(exit(_))
    .requestAsync(endpoints.Workspace.buildTargets)(buildTargets(_))
    .requestAsync(endpoints.BuildTarget.dependencySources)(dependencySources(_))
    .requestAsync(endpoints.BuildTarget.sources)(sources(_))
    .requestAsync(endpoints.BuildTarget.scalacOptions)(scalacOptions(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))
    .requestAsync(endpoints.BuildTarget.test)(test(_))
    .requestAsync(endpoints.BuildTarget.run)(run(_))

  // Internal state, initial value defaults to
  @volatile private var currentState: State = callSiteState

  /** Returns the final state after BSP commands that can be cached by bloop. */
  def stateAfterExecution: State = {
    // Use logger of the initial state instead of the bsp forwarder logger
    currentState.copy(logger = callSiteState.logger)
  }

  private def reloadState(config: AbsolutePath): Task[State] = {
    val pool = currentState.pool
    val defaultOpts = currentState.commonOptions
    bspLogger.debug(s"Reloading bsp state for ${config.syntax}")
    // TODO(jvican): Modify the in, out and err streams to go through the BSP wire
    State.loadActiveStateFor(config, pool, defaultOpts, bspLogger).map { state0 =>
      val newState = state0.copy(logger = bspLogger)
      currentState = newState
      newState
    }
  }

  private def saveState(state: State): Task[Unit] = {
    Task {
      val configDir = state.build.origin
      bspLogger.debug(s"Saving bsp state for ${configDir.syntax}")
      // Spawn the analysis persistence after every save
      val persistOut = (msg: String) => bspLogger.debug(msg)
      Tasks.persist(state, persistOut).runAsync(ExecutionContext.scheduler)
      // Save the state globally so that it can be accessed by other clients
      State.stateCache.updateBuild(state)
      ()
    }
  }

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param params The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(
      params: bsp.InitializeBuildParams): BspEndpointResponse[bsp.InitializeBuildResult] = {
    val uri = new java.net.URI(params.rootUri.value)
    val configDir = AbsolutePath(uri).resolve(relativeConfigPath)
    reloadState(configDir).map { state =>
      callSiteState.logger.info("request received: build/initialize")
      Right(
        bsp.InitializeBuildResult(
          BuildInfo.bloopName,
          BuildInfo.version,
          BuildInfo.bspVersion,
          bsp.BuildServerCapabilities(
            compileProvider = Some(BloopBspServices.DefaultCompileProvider),
            testProvider = Some(BloopBspServices.DefaultTestProvider),
            runProvider = Some(BloopBspServices.DefaultRunProvider),
            inverseSourcesProvider = Some(true),
            dependencySourcesProvider = Some(true),
            resourcesProvider = Some(false),
            buildTargetChangedProvider = Some(false)
          ),
          None
        )
      )
    }
  }

  val isInitialized = scala.concurrent.Promise[BspResponse[Unit]]()
  val isInitializedTask = Task.fromFuture(isInitialized.future).memoize
  def initialized(
      initializedBuildParams: bsp.InitializedBuildParams
  ): Unit = {
    isInitialized.success(Right(()))
    callSiteState.logger.info("BSP initialization handshake complete.")
  }

  def ifInitialized[T](compute: BspComputation[T]): BspEndpointResponse[T] = {
    // Give a time window for `isInitialized` to complete, otherwise assume it didn't happen
    isInitializedTask
      .timeoutTo(
        FiniteDuration(1, TimeUnit.SECONDS),
        Task.now(Left(JsonRpcResponse.invalidRequest("The session has not been initialized.")))
      )
      .flatMap {
        case Left(e) => Task.now(Left(e))
        case Right(_) =>
          reloadState(currentState.build.origin).flatMap { state =>
            compute(state).flatMap { case (state, e) => saveState(state).map(_ => e) }
          }
      }
  }

  def mapToProject(
      target: bsp.BuildTargetIdentifier,
      state: State
  ): Either[String, ProjectMapping] = {
    val uri = target.uri
    ProjectUris.getProjectDagFromUri(uri.value, state) match {
      case Left(errorMsg) => Left(errorMsg)
      case Right(Some(project)) => Right((target, project))
      case Right(None) => Left(s"No project associated with $uri")
    }
  }

  type ProjectMapping = (bsp.BuildTargetIdentifier, Project)
  private def mapToProjects(
      targets: Seq[bsp.BuildTargetIdentifier],
      state: State
  ): Either[String, Seq[ProjectMapping]] = {
    targets.headOption match {
      case Some(head) =>
        val init = mapToProject(head, state).map(m => m :: Nil)
        targets.tail.foldLeft(init) {
          case (acc, t) => acc.flatMap(ms => mapToProject(t, state).map(m => m :: ms))
        }
      case None =>
        Left("Empty build targets. Expected at least one build target identifier.")
    }
  }

  /**
   * Keep track of those projects that were compiled at least once so that we can
   * decide to enable fresh reporting for projects that are compiled for the first time.
   *
   * Required by https://github.com/scalacenter/bloop/issues/726
   */
  private val compiledTargetsAtLeastOnce = new TrieMap[bsp.BuildTargetIdentifier, Boolean]()

  def compileProjects(
      userProjects: Seq[ProjectMapping],
      state: State,
      compileArgs: List[String]
  ): BspResult[bsp.CompileResult] = {
    def reportError(p: Project, problems: List[Problem], elapsedMs: Long): String = {
      // Don't show warnings in this "final report", we're handling them in the reporter
      val count = bloop.reporter.Problem.count(problems)
      s"${p.name} [${elapsedMs}ms] (errors ${count.errors})"
    }

    val pipeline = compileArgs.exists(_ == "--pipeline")
    def compile(projects: List[Project]): Task[State] = {
      val cwd = state.build.origin.getParent
      val config = ReporterConfig.defaultFormat.copy(reverseOrder = false)
      val createReporter = (project: Project, cwd: AbsolutePath) => {
        val btid = bsp.BuildTargetIdentifier(project.bspUri)
        val reportAllPreviousProblems = {
          compiledTargetsAtLeastOnce.putIfAbsent(btid, true) match {
            case Some(_) => false
            case None => true
          }
        }

        new BspProjectReporter(
          project,
          bspLogger,
          cwd,
          identity,
          config,
          reportAllPreviousProblems
        )
      }

      val dag = Aggregate(projects.map(p => state.build.getDagFor(p)))
      CompilationTask.compile(state, dag, createReporter, CompileMode.Sequential, pipeline, false)
    }

    val projects: List[Project] = {
      val projects0 = Dag.reduce(state.build.dags, userProjects.map(_._2).toSet).toList
      if (!compileArgs.exists(_ == "--cascade")) projects0
      else Dag.inverseDependencies(state.build.dags, projects0).reduced
    }

    compile(projects).map { newState =>
      val compiledResults = state.results.diffLatest(newState.results)
      val errorMsgs = compiledResults.flatMap {
        case (p, result) =>
          result match {
            case Compiler.Result.Empty => Nil
            case Compiler.Result.Blocked(_) => Nil
            case Compiler.Result.Success(_, _, _) => Nil
            case Compiler.Result.GlobalError(problem) => List(problem)
            case Compiler.Result.Cancelled(problems, elapsed) =>
              List(reportError(p, problems, elapsed))
            case Compiler.Result.Failed(problems, t, elapsed) =>
              val acc = List(reportError(p, problems, elapsed))
              t match {
                case Some(t) => s"Bloop error when compiling ${p.name}: '${t.getMessage}'" :: acc
                case None => acc
              }
          }
      }

      // TODO(jvican): Support cancelled status code
      val response = errorMsgs match {
        case Nil => Right(bsp.CompileResult(None, bsp.StatusCode.Ok, None))
        case xs => Right(bsp.CompileResult(None, bsp.StatusCode.Error, None))
      }

      (newState, response)
    }
  }

  def compile(params: bsp.CompileParams): BspEndpointResponse[bsp.CompileResult] = {
    ifInitialized { (state: State) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          bspLogger.error(error)
          Task.now((state, Right(bsp.CompileResult(None, bsp.StatusCode.Error, None))))
        case Right(mappings) =>
          val compileArgs = params.arguments.getOrElse(Nil)
          compileProjects(mappings, state, compileArgs)
      }
    }
  }

  def test(params: bsp.TestParams): BspEndpointResponse[bsp.TestResult] = {
    def test(
        id: BuildTargetIdentifier,
        project: Project,
        state: State
    ): Task[State] = {
      val testFilter = TestInternals.parseFilters(Nil) // Don't support test only for now
      val handler = new BspLoggingEventHandler(id, state.logger, client)
      Tasks.test(state, List(project), Nil, testFilter, handler)
    }

    ifInitialized { (state: State) =>
      mapToProjects(params.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          bspLogger.error(error)
          Task.now((state, Right(bsp.TestResult(None, bsp.StatusCode.Error, None))))
        case Right(mappings) =>
          val args = params.arguments.getOrElse(Nil)
          compileProjects(mappings, state, args).flatMap {
            case (newState, compileResult) =>
              compileResult match {
                case Right(result) =>
                  val sequentialTestExecution = mappings.foldLeft(Task.now(newState)) {
                    case (taskState, (tid, p)) => taskState.flatMap(state => test(tid, p, state))
                  }

                  sequentialTestExecution.materialize.map(_.toEither).map {
                    case Right(newState) =>
                      (newState, Right(bsp.TestResult(None, bsp.StatusCode.Ok, None)))
                    case Left(e) =>
                      //(newState, Right(bsp.TestResult(None, bsp.StatusCode.Error, None)))
                      val errorMessage =
                        JsonRpcResponse.internalError(s"Failed test execution: ${e.getMessage}")
                      (newState, Left(errorMessage))
                  }

                case Left(error) => Task.now((newState, Left(error)))
              }
          }
      }
    }
  }

  def run(params: bsp.RunParams): BspEndpointResponse[bsp.RunResult] = {
    def run(
        id: BuildTargetIdentifier,
        project: Project,
        state: State
    ): Task[State] = {
      import bloop.engine.tasks.LinkTask.{linkMainWithJs, linkMainWithNative}
      val cwd = state.commonOptions.workingPath
      val cmd = Commands.Run(List(project.name))
      Interpreter.getMainClass(state, project, cmd.main) match {
        case Left(state) =>
          Task.now(sys.error(s"Failed to run main class in ${project.name}"))
        case Right(mainClass) =>
          project.platform match {
            case Platform.Jvm(javaEnv, _, _) =>
              Tasks.runJVM(state, project, javaEnv, cwd, mainClass, cmd.args.toArray, false)
            case platform @ Platform.Native(config, _, _) =>
              val target = ScalaNativeToolchain.linkTargetFrom(project, config)
              linkMainWithNative(cmd, project, state, mainClass, target, platform).flatMap {
                state =>
                  val args = (target.syntax +: cmd.args).toArray
                  if (!state.status.isOk) Task.now(state)
                  else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
              }
            case platform @ Platform.Js(config, _, _) =>
              val target = ScalaJsToolchain.linkTargetFrom(project, config)
              linkMainWithJs(cmd, project, state, mainClass, target, platform).flatMap { state =>
                // We use node to run the program (is this a special case?)
                val args = ("node" +: target.syntax +: cmd.args).toArray
                if (!state.status.isOk) Task.now(state)
                else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
              }
          }
      }
    }

    ifInitialized { (state: State) =>
      mapToProject(params.target, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          bspLogger.error(error)
          Task.now((state, Right(bsp.RunResult(None, bsp.StatusCode.Error))))
        case Right((tid, project)) =>
          val args = params.arguments.getOrElse(Nil)
          compileProjects(List((tid, project)), state, args).flatMap {
            case (newState, compileResult) =>
              compileResult match {
                case Right(result) =>
                  var isCancelled: Boolean = false
                  run(tid, project, newState)
                    .doOnCancel(Task { isCancelled = true; () })
                    .materialize
                    .map(_.toEither)
                    .map {
                      case Left(e) =>
                        val errorMsg =
                          JsonRpcResponse.internalError(s"Failed test execution: ${e.getMessage}")
                        (state, Left(errorMsg))
                      case Right(state) =>
                        val status = {
                          val exitStatus = state.status
                          if (isCancelled) bsp.StatusCode.Cancelled
                          else if (exitStatus.isOk) bsp.StatusCode.Ok
                          else bsp.StatusCode.Error
                        }
                        (state, Right(bsp.RunResult(None, status)))
                    }

                case Left(error) => Task.now((state, Left(error)))
              }
          }
      }
    }
  }

  private def toBuildTargetId(project: Project): bsp.BuildTargetIdentifier =
    bsp.BuildTargetIdentifier(project.bspUri)

  def toScalaBuildTarget(project: Project, instance: ScalaInstance): bsp.ScalaBuildTarget = {
    def toBinaryScalaVersion(version: String): String = {
      version.split('.').take(2).mkString(".")
    }

    val jars = instance.allJars.iterator.map(j => bsp.Uri(j.toPath.toUri)).toList
    val platform = project.platform match {
      case _: Platform.Jvm => bsp.ScalaPlatform.Jvm
      case _: Platform.Js => bsp.ScalaPlatform.Js
      case _: Platform.Native => bsp.ScalaPlatform.Native
    }

    bsp.ScalaBuildTarget(
      scalaOrganization = instance.organization,
      scalaVersion = instance.version,
      scalaBinaryVersion = toBinaryScalaVersion(instance.version),
      platform = platform,
      jars = jars
    )
  }

  def buildTargets(
      request: bsp.WorkspaceBuildTargetsRequest
  ): BspEndpointResponse[bsp.WorkspaceBuildTargets] = {
    ifInitialized { (state: State) =>
      def reportBuildError(msg: String): Unit = {
        endpoints.Build.showMessage.notify(
          ShowMessageParams(MessageType.Error, None, None, msg)
        )(client)
        ()
      }

      Validate.validateBuildForCLICommands(state, reportBuildError(_)).flatMap { state =>
        if (state.status == ExitStatus.BuildDefinitionError)
          Task.now((state, Right(WorkspaceBuildTargets(Nil))))
        else {
          val build = state.build
          val targets = bsp.WorkspaceBuildTargets(
            build.projects.map { p =>
              val id = toBuildTargetId(p)
              val tag = {
                if (p.name.endsWith("-test") && build.getProjectFor(s"${p.name}-test").isEmpty)
                  bsp.BuildTargetTag.Test
                else bsp.BuildTargetTag.Library
              }
              val deps = p.dependencies.iterator.flatMap(build.getProjectFor(_).toList)
              val extra = p.scalaInstance.map(i => encodeScalaBuildTarget(toScalaBuildTarget(p, i)))
              val capabilities = bsp.BuildTargetCapabilities(
                canCompile = true,
                canTest = true,
                canRun = true
              )
              bsp.BuildTarget(
                id = id,
                displayName = Some(p.name),
                baseDirectory = Some(bsp.Uri(p.baseDirectory.toBspUri)),
                tags = List(tag),
                languageIds = BloopBspServices.DefaultLanguages,
                dependencies = deps.map(toBuildTargetId).toList,
                capabilities = capabilities,
                data = extra
              )
            }
          )

          Task.now((state, Right(targets)))
        }
      }
    }
  }

  def sources(
      request: bsp.SourcesParams
  ): BspEndpointResponse[bsp.SourcesResult] = {
    def sources(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.SourcesResult] = {
      val response = bsp.SourcesResult(
        projects.iterator.map {
          case (target, project) =>
            val sources = project.sources.map { s =>
              // TODO(jvican): Don't default on false for generated, add this info to JSON fields
              bsp.SourceItem(bsp.Uri(s.toBspSourceUri), false)
            }
            bsp.SourcesItem(target, sources)
        }.toList
      )

      Task.now((state, Right(response)))
    }

    ifInitialized { (state: State) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          bspLogger.error(error)
          Task.now((state, Right(bsp.SourcesResult(Nil))))
        case Right(mappings) => sources(mappings, state)
      }
    }
  }

  def dependencySources(
      request: bsp.DependencySourcesParams
  ): BspEndpointResponse[bsp.DependencySourcesResult] = {
    def sources(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.DependencySourcesResult] = {
      val response = bsp.DependencySourcesResult(
        projects.iterator.map {
          case (target, project) =>
            val sourceJars = project.resolution.toList.flatMap { res =>
              res.modules.flatMap { m =>
                m.artifacts.iterator
                  .filter(a => a.classifier.toList.contains("sources"))
                  .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri))
                  .toList
              }
            }
            bsp.DependencySourcesItem(target, sourceJars)
        }.toList
      )

      Task.now((state, Right(response)))
    }

    ifInitialized { (state: State) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          bspLogger.error(error)
          Task.now((state, Right(bsp.DependencySourcesResult(Nil))))
        case Right(mappings) => sources(mappings, state)
      }
    }
  }

  def scalacOptions(
      request: bsp.ScalacOptionsParams): BspEndpointResponse[bsp.ScalacOptionsResult] = {
    def scalacOptions(
        projects: Seq[ProjectMapping],
        state: State
    ): BspResult[bsp.ScalacOptionsResult] = {
      val response = bsp.ScalacOptionsResult(
        projects.iterator.map {
          case (target, project) =>
            bsp.ScalacOptionsItem(
              target = target,
              options = project.scalacOptions.toList,
              classpath = project.compilationClasspath.map(e => bsp.Uri(e.toBspUri)).toList,
              classDirectory = bsp.Uri(project.classesDir.toBspUri)
            )
        }.toList
      )

      Task.now((state, Right(response)))
    }

    ifInitialized { (state: State) =>
      mapToProjects(request.targets, state) match {
        case Left(error) =>
          // Log the mapping error to the user via a log event + an error status code
          bspLogger.error(error)
          // TODO(jvican): Add status code to scalac options result
          Task.now((state, Right(bsp.ScalacOptionsResult(Nil))))
        case Right(mappings) => scalacOptions(mappings, state)
      }
    }
  }

  val isShutdown = scala.concurrent.Promise[BspResponse[Unit]]()
  val isShutdownTask = Task.fromFuture(isShutdown.future).memoize
  def shutdown(shutdown: bsp.Shutdown): Unit = {
    isShutdown.success(Right(()))
    callSiteState.logger.info("shutdown request received: build/shutdown")
    ()
  }

  def exit(shutdown: bsp.Exit): Task[Unit] = {
    def closeServices(code: Int): Unit = {
      exitStatus.set(code)
      // Closing the input stream is our way to stopping these services
      try socketInput.close()
      catch { case t: Throwable => () }
      ()
    }

    isShutdownTask
      .timeoutTo(
        FiniteDuration(1, TimeUnit.SECONDS),
        Task.now(Left(()))
      )
      .map {
        case Left(_) => closeServices(1)
        case Right(_) => closeServices(0)
      }
  }
}

object BloopBspServices {
  private[bloop] val counter: AtomicInteger = new AtomicInteger(0)
  private[bloop] val DefaultLanguages = List("scala", "java")
  private[bloop] val DefaultCompileProvider = bsp.CompileProvider(DefaultLanguages)
  private[bloop] val DefaultTestProvider = bsp.TestProvider(DefaultLanguages)
  private[bloop] val DefaultRunProvider = bsp.RunProvider(DefaultLanguages)
}
