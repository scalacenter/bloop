package bloop.bsp

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import bloop.{Compiler, Project}
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Action, Dag, Exit, Interpreter, Run, State}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.BspLogger
import monix.eval.Task
import ch.epfl.scala.bsp.endpoints
import scala.meta.jsonrpc.{JsonRpcClient, Response => JsonRpcResponse, Services => JsonRpcServices}
import xsbti.Problem

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.ScalaBuildTarget.encodeScalaBuildTarget

import scala.concurrent.duration.FiniteDuration

final class BloopBspServices(
    callSiteState: State,
    client: JsonRpcClient,
    relativeConfigPath: RelativePath
) {
  private type ProtocolError = JsonRpcResponse.Error
  private type BspResponse[T] = Task[Either[ProtocolError, T]]

  // Disable ansii codes for now so that the BSP clients don't get unescaped color codes
  private val bspForwarderLogger = BspLogger(callSiteState, client, false)
  final val services = JsonRpcServices.empty
    .requestAsync(endpoints.Build.initialize)(initialize(_))
    .notification(endpoints.Build.initialized)(initialized(_))
    .request(endpoints.Build.shutdown)(shutdown(_))
    .notificationAsync(endpoints.Build.exit)(exit(_))
    .requestAsync(endpoints.Workspace.buildTargets)(buildTargets(_))
    .requestAsync(endpoints.BuildTarget.dependencySources)(dependencySources(_))
    .requestAsync(endpoints.BuildTarget.scalacOptions)(scalacOptions(_))
    .requestAsync(endpoints.BuildTarget.compile)(compile(_))

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

  type ProjectMapping = (bsp.BuildTargetIdentifier, Project)
  private def mapToProjects(
      targets: Seq[bsp.BuildTargetIdentifier]): Either[ProtocolError, Seq[ProjectMapping]] = {
    def getProject(target: bsp.BuildTargetIdentifier): Either[ProtocolError, ProjectMapping] = {
      val uri = target.uri
      ProjectUris.getProjectDagFromUri(uri.value, currentState) match {
        case Left(errorMsg) => Left(JsonRpcResponse.parseError(errorMsg))
        case Right(Some(project)) => Right((target, project))
        case Right(None) => Left(JsonRpcResponse.invalidRequest(s"No project associated with $uri"))
      }
    }

    targets.headOption match {
      case Some(head) =>
        val init = getProject(head).map(m => m :: Nil)
        targets.tail.foldLeft(init) {
          case (acc, t) => acc.flatMap(ms => getProject(t).map(m => m :: ms))
        }
      case None =>
        Left(
          JsonRpcResponse.invalidRequest(
            "Empty build targets. Expected at least one build target identifier."))
    }
  }

  def compile(params: bsp.CompileParams): BspResponse[bsp.CompileResult] = {
    def compile(projects0: Seq[ProjectMapping]): BspResponse[bsp.CompileResult] = {
      val current = currentState
      val projects = Dag.reduce(current.build.dags, projects0.map(_._2).toSet)
      val action = projects.foldLeft(Exit(ExitStatus.Ok): Action) {
        case (action, project) => Run(Commands.Compile(project.name), action)
      }

      def reportError(p: Project, problems: Array[Problem], elapsedMs: Long): String = {
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
              case Compiler.Result.Failed(problems, elapsed) =>
                List(reportError(p, problems, elapsed))
            }
        }

        errorMsgs match {
          case Nil => Right(bsp.CompileResult(None, None))
          case xs =>
            Left(JsonRpcResponse.internalError(s"""Compilation failed:
                                                  |${xs
                                                    .map(str => s"  ${str}")
                                                    .mkString("\n")}
            """.stripMargin))
        }
      }
    }

    ifInitialized {
      mapToProjects(params.targets) match {
        case Left(error) => Task.now(Left(error))
        case Right(mappings) => compile(mappings)
      }
    }
  }

  private def toBuildTargetId(project: Project): bsp.BuildTargetIdentifier =
    bsp.BuildTargetIdentifier(project.bspUri)

  def toScalaBuildTarget(project: Project): bsp.ScalaBuildTarget = {
    val instance = project.scalaInstance
    val jars = instance.allJars.iterator.map(j => bsp.Uri(j.toURI)).toList
    bsp.ScalaBuildTarget(
      scalaOrganization = instance.organization,
      scalaVersion = instance.version,
      scalaBinaryVersion = instance.version,
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
          val extra = Some(encodeScalaBuildTarget(toScalaBuildTarget(p)))
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
                  .filter(a => a.classifier == "sources")
                  .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri))
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
              classpath = project.classpath.iterator.map(e => bsp.Uri(e.toBspUri)).toList,
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
    isShutdownTask
      .timeoutTo(
        FiniteDuration(1, TimeUnit.SECONDS),
        Task.now(Left(()))
      ).flatMap {
      case Left(_) => throw BloopBspServices.BloopExitGracefully(1)
      case Right(_) => throw BloopBspServices.BloopExitGracefully(0)
    }
  }
}

object BloopBspServices {
  private[bloop] val counter: AtomicInteger = new AtomicInteger(0)
  private[bloop] val DefaultLanguages = List("scala", "java")
  private[bloop] val DefaultCompileProvider = bsp.CompileProvider(DefaultLanguages)
  private[bloop] val DefaultTestProvider = bsp.TestProvider(DefaultLanguages)
  private[bloop] val DefaultRunProvider = bsp.RunProvider(DefaultLanguages)

  // We need to use an exception to stop the server because lsp4s doesn't give us something better
  private[bloop] case class BloopExitGracefully(code: Int) extends Exception
}
