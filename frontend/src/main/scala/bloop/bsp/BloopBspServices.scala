package bloop.bsp

import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import bloop.{Compiler, ScalaInstance}
import bloop.cli.{Commands, ExitStatus}
import bloop.config.Config.Platform
import bloop.data.Project
import bloop.engine.tasks.{ScalaJsToolchain, ScalaNativeToolchain, Tasks}
import bloop.engine.{Action, Dag, Exit, Interpreter, Run, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.BspServerLogger
import bloop.testing.{BspLoggingEventHandler, TestInternals}
import monix.eval.Task
import ch.epfl.scala.bsp.{BuildTargetIdentifier, endpoints}

import scala.meta.jsonrpc.{JsonRpcClient, Response => JsonRpcResponse, Services => JsonRpcServices}
import xsbti.Problem
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.ScalaBuildTarget.encodeScalaBuildTarget
import monix.execution.atomic.AtomicInt

import scala.concurrent.duration.FiniteDuration

final class BloopBspServices(
    callSiteState: State,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath,
    socketInput: InputStream,
    exitStatus: AtomicInt
) {
  private type ProtocolError = JsonRpcResponse.Error
  private type BspResponse[T] = Task[Either[ProtocolError, T]]

  // Disable ansii codes for now so that the BSP clients don't get unescaped color codes
  private val bspForwarderLogger = BspServerLogger(callSiteState, client, false)
  final val services = JsonRpcServices
    .empty(bspForwarderLogger)
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .request(endpoints.Build.shutdown)(shutdown(_))
    .notificationAsync(endpoints.Build.exit)(exit(_))
    .requestAsync(endpoints.Workspace.buildTargets)(buildTargets(_))
    .requestAsync(endpoints.BuildTarget.dependencySources)(dependencySources(_))
    .requestAsync(endpoints.BuildTarget.scalacOptions)(scalacOptions(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))
    .requestAsync(endpoints.BuildTarget.test)(test(_))
    .requestAsync(endpoints.BuildTarget.run)(run(_))

  // Internal state, think how to make this more elegant.
  @volatile private var currentState: State = null

  /**
   * Get the latest state that can be reused and cached by bloop so that the next client
   * can have access to it.
   */
  def latestState: State = {
    val state0 = if (currentState == null) callSiteState else currentState
    state0.copy(logger = callSiteState.logger)
  }

  private val pool = callSiteState.pool
  private val defaultOpts = callSiteState.commonOptions
  def reloadState(config: AbsolutePath): Task[State] = {
    bspForwarderLogger.debug(s"Reloading bsp state for ${config.syntax}")
    State.loadActiveStateFor(config, pool, defaultOpts, bspForwarderLogger).map { state0 =>
      state0.copy(logger = bspForwarderLogger, commonOptions = latestState.commonOptions)
    }
  }

  /**
   * Implements the initialize method that is the first pass of the Client-Server handshake.
   *
   * @param params The params request that we get from the client.
   * @return An async computation that returns the response to the client.
   */
  def initialize(params: bsp.InitializeBuildParams): BspResponse[bsp.InitializeBuildResult] = {
    val uri = new java.net.URI(params.rootUri.value)
    val configDir = AbsolutePath(uri).resolve(relativeConfigPath)
    reloadState(configDir).map { state =>
      callSiteState.logger.info("request received: build/initialize")
      currentState = state
      Right(
        bsp.InitializeBuildResult(
          bsp.BuildServerCapabilities(
            compileProvider = BloopBspServices.DefaultCompileProvider,
            testProvider = BloopBspServices.DefaultTestProvider,
            runProvider = BloopBspServices.DefaultRunProvider,
            textDocumentBuildTargetsProvider = true,
            dependencySourcesProvider = true,
            resourcesProvider = false,
            buildTargetChangedProvider = false
          )
        )
      )
    }
  }

  val isInitialized = scala.concurrent.Promise[Either[ProtocolError, Unit]]()
  val isInitializedTask = Task.fromFuture(isInitialized.future).memoize
  def initialized(
      initializedBuildParams: bsp.InitializedBuildParams
  ): Unit = {
    isInitialized.success(Right(()))
    callSiteState.logger.info("BSP initialization handshake complete.")
  }

  def ifInitialized[T](t: => BspResponse[T]): BspResponse[T] = {
    // Give a time window for `isInitialized` to complete, otherwise assume it didn't happen
    isInitializedTask
      .timeoutTo(
        FiniteDuration(1, TimeUnit.SECONDS),
        Task.now(Left(JsonRpcResponse.invalidRequest("The session has not been initialized.")))
      )
      .flatMap {
        case Left(e) => Task.now(Left(e))
        case Right(_) => t
      }
  }

  def mapToProject(target: bsp.BuildTargetIdentifier): Either[ProtocolError, ProjectMapping] = {
    val uri = target.uri
    ProjectUris.getProjectDagFromUri(uri.value, currentState) match {
      case Left(errorMsg) => Left(JsonRpcResponse.parseError(errorMsg))
      case Right(Some(project)) => Right((target, project))
      case Right(None) => Left(JsonRpcResponse.invalidRequest(s"No project associated with $uri"))
    }
  }

  type ProjectMapping = (bsp.BuildTargetIdentifier, Project)
  private def mapToProjects(
      targets: Seq[bsp.BuildTargetIdentifier]): Either[ProtocolError, Seq[ProjectMapping]] = {
    targets.headOption match {
      case Some(head) =>
        val init = mapToProject(head).map(m => m :: Nil)
        targets.tail.foldLeft(init) {
          case (acc, t) => acc.flatMap(ms => mapToProject(t).map(m => m :: ms))
        }
      case None =>
        Left(
          JsonRpcResponse.invalidRequest(
            "Empty build targets. Expected at least one build target identifier."))
    }
  }

  def compileProjects(projects0: Seq[ProjectMapping]): BspResponse[bsp.CompileResult] = {
    val current = currentState
    val projects = Dag.reduce(current.build.dags, projects0.map(_._2).toSet)
    val action = projects.foldLeft(Exit(ExitStatus.Ok): Action) {
      case (action, project) => Run(Commands.Compile(project.name), action)
    }

    def reportError(p: Project, problems: List[Problem], elapsedMs: Long): String = {
      val count = bloop.reporter.Problem.count(problems)
      s"${p.name} [${elapsedMs}ms] (errors ${count.errors}, warnings ${count.warnings})"
    }

    Interpreter.execute(action, Task.now(current)).map { state =>
      currentState = state
      val compiledProjects = current.results.diffLatest(state.results)
      val errorMsgs = compiledProjects.flatMap {
        case (p, result) =>
          result match {
            case Compiler.Result.Empty => Nil
            case Compiler.Result.Cancelled(_) => Nil
            case Compiler.Result.Blocked(_) => Nil
            case Compiler.Result.Success(_, _, _) => Nil
            case Compiler.Result.GlobalError(problem) => List(problem)
            case Compiler.Result.Failed(problems, t, elapsed) =>
              val acc = List(reportError(p, problems, elapsed))
              t match {
                case Some(t) => s"Bloop error when compiling ${p.name}: '${t.getMessage}'" :: acc
                case None => acc
              }
          }
      }

      errorMsgs match {
        case Nil => Right(bsp.CompileResult(None, None))
        case xs =>
          val allErrors = xs.map(str => s"  ${str}").mkString(System.lineSeparator())
          Left(
            JsonRpcResponse.internalError(
              s"Compilation failed:${System.lineSeparator()}$allErrors".stripMargin))
      }
    }
  }

  def compile(params: bsp.CompileParams): BspResponse[bsp.CompileResult] = {
    ifInitialized {
      mapToProjects(params.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) => compileProjects(mappings)
      }
    }
  }

  def test(params: bsp.TestParams): BspResponse[bsp.TestResult] = {
    def test(
        id: BuildTargetIdentifier,
        project: Project,
        state: State
    ): Task[State] = {
      val testFilter = TestInternals.parseFilters(Nil) // Don't support test only for now
      val cwd = state.commonOptions.workingPath
      val handler = new BspLoggingEventHandler(id, state.logger, client)
      Tasks.test(state, project, cwd, false, Nil, testFilter, handler)
    }

    ifInitialized {
      mapToProjects(params.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) =>
          compileProjects(mappings).flatMap { compileResult =>
            compileResult match {
              case Right(result) =>
                val sequentialTestExecution = mappings.foldLeft(Task.now(currentState)) {
                  case (taskState, (tid, p)) => taskState.flatMap(state => test(tid, p, state))
                }

                sequentialTestExecution.materialize.map(_.toEither).map {
                  case Left(e) =>
                    Left(JsonRpcResponse.internalError(s"Failed test execution: ${e.getMessage}"))
                  case Right(state) =>
                    currentState = state
                    Right(bsp.TestResult(None, None))
                }

              case Left(error) => Task.now(Left(error))
            }
          }
      }
    }
  }

  def run(params: bsp.RunParams): BspResponse[bsp.RunResult] = {
    def run(
        id: BuildTargetIdentifier,
        project: Project,
        state: State
    ): Task[State] = {
      import Interpreter.{linkWithScalaJs, linkWithScalaNative}
      val cwd = state.commonOptions.workingPath
      val cmd = Commands.Run(project.name)
      Interpreter.getMainClass(state, project, cmd.main) match {
        case Left(state) =>
          Task.now(sys.error(s"Failed to run main class in ${project.name}"))
        case Right(mainClass) =>
          project.platform match {
            case Platform.Native(config, _) =>
              val target = ScalaNativeToolchain.linkTargetFrom(config, project.out)
              linkWithScalaNative(cmd, project, state, mainClass, target, config).flatMap { state =>
                val args = (target.syntax +: cmd.args).toArray
                if (!state.status.isOk) Task.now(state)
                else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
              }
            case Platform.Js(config, _) =>
              val target = ScalaJsToolchain.linkTargetFrom(config, project.out)
              linkWithScalaJs(cmd, project, state, mainClass, target, config).flatMap { state =>
                // We use node to run the program (is this a special case?)
                val args = ("node" +: target.syntax +: cmd.args).toArray
                if (!state.status.isOk) Task.now(state)
                else Tasks.runNativeOrJs(state, project, cwd, mainClass, args)
              }
            case Platform.Jvm(_, _) =>
              Tasks.runJVM(state, project, cwd, mainClass, cmd.args.toArray)
          }
      }
    }

    ifInitialized {
      mapToProject(params.target) match {
        case Left(error) => Task.now(Left(error))
        case Right((tid, project)) =>
          compileProjects(List((tid, project))).flatMap { compileResult =>
            compileResult match {
              case Right(result) =>
                var isCancelled: Boolean = false
                val runTask = run(tid, project, currentState)
                  .doOnCancel(Task { isCancelled = true; () })

                runTask.materialize.map(_.toEither).map {
                  case Left(e) =>
                    Left(JsonRpcResponse.internalError(s"Failed test execution: ${e.getMessage}"))
                  case Right(state) =>
                    currentState = state
                    val status = {
                      val exitStatus = state.status
                      if (isCancelled) bsp.StatusCode.Cancelled
                      else if (exitStatus.isOk) bsp.StatusCode.Ok
                      else bsp.StatusCode.Error
                    }
                    Right(bsp.RunResult(None, status))
                }

              case Left(error) => Task.now(Left(error))
            }
          }
      }
    }
  }

  private def toBuildTargetId(project: Project): bsp.BuildTargetIdentifier =
    bsp.BuildTargetIdentifier(project.bspUri)

  def toScalaBuildTarget(instance: ScalaInstance): bsp.ScalaBuildTarget = {
    def toBinaryScalaVersion(version: String): String = {
      version.split('.').take(2).mkString(".")
    }

    val jars = instance.allJars.iterator.map(j => bsp.Uri(j.toURI)).toList
    bsp.ScalaBuildTarget(
      scalaOrganization = instance.organization,
      scalaVersion = instance.version,
      scalaBinaryVersion = toBinaryScalaVersion(instance.version),
      platform = bsp.ScalaPlatform.Jvm,
      jars = jars
    )
  }

  def buildTargets(
      request: bsp.WorkspaceBuildTargetsRequest): BspResponse[bsp.WorkspaceBuildTargets] = {
    ifInitialized {
      val build = currentState.build
      val targets = bsp.WorkspaceBuildTargets(
        build.projects.map { p =>
          val id = toBuildTargetId(p)
          val kind = {
            if (p.name.endsWith("-test") && build.getProjectFor(s"${p.name}-test").isEmpty)
              bsp.BuildTargetKind.Test
            else bsp.BuildTargetKind.Library
          }
          val deps = p.dependencies.iterator.flatMap(build.getProjectFor(_).toList)
          val extra = p.scalaInstance.map(i => encodeScalaBuildTarget(toScalaBuildTarget(i)))
          val capabilities = bsp.BuildTargetCapabilities(
            canCompile = true,
            canTest = true,
            canRun = true
          )
          bsp.BuildTarget(
            id = id,
            displayName = p.name,
            kind = kind,
            languageIds = BloopBspServices.DefaultLanguages,
            dependencies = deps.map(toBuildTargetId).toList,
            capabilities = capabilities,
            data = extra
          )
        }
      )

      Task.now(Right(targets))
    }
  }

  def dependencySources(
      request: bsp.DependencySourcesParams): BspResponse[bsp.DependencySourcesResult] = {
    def sources(projects: Seq[ProjectMapping]): BspResponse[bsp.DependencySourcesResult] = {
      val response = bsp.DependencySourcesResult(
        projects.iterator.map {
          case (target, project) =>
            val sources = project.sources.iterator.map(s => bsp.Uri(s.toBspUri)).toList
            val sourceJars = project.resolution.toList.flatMap { res =>
              res.modules.flatMap { m =>
                m.artifacts.iterator
                  .filter(a => a.classifier.toList.contains("sources"))
                  .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri))
                  .toList
              }
            }
            bsp.DependencySourcesItem(target, sources ++ sourceJars)
        }.toList
      )

      Task.now(Right(response))
    }

    ifInitialized {
      mapToProjects(request.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) => sources(mappings)
      }
    }
  }

  def scalacOptions(request: bsp.ScalacOptionsParams): BspResponse[bsp.ScalacOptionsResult] = {
    def scalacOptions(projects: Seq[ProjectMapping]): BspResponse[bsp.ScalacOptionsResult] = {
      val response = bsp.ScalacOptionsResult(
        projects.iterator.map {
          case (target, project) =>
            bsp.ScalacOptionsItem(
              target = target,
              options = project.scalacOptions.toList,
              classpath = project.classpath.map(e => bsp.Uri(e.toBspUri)).toList,
              classDirectory = bsp.Uri(project.classesDir.toBspUri)
            )
        }.toList
      )

      Task.now(Right(response))
    }

    ifInitialized {
      mapToProjects(request.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) => scalacOptions(mappings)
      }
    }
  }

  val isShutdown = scala.concurrent.Promise[Either[ProtocolError, Unit]]()
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
