package bloop.integrations.sbt

import xsbti.compile.MiniSetup
import xsbti.compile.CompileAnalysis
import xsbti.{Reporter => CompileReporter}
import xsbti.compile.{Setup => CompileSetup}
import xsbti.compile.CompileResult
import xsbti.compile.CompileOrder

import sbt.std.TaskExtra
import sbt.internal.inc.LoggedReporter
import sbt.internal.inc.Analysis
import sbt.{
  Def,
  Task,
  TaskKey,
  ScopedKey,
  SettingKey,
  Compile,
  Test,
  Keys,
  File,
  Classpaths,
  Logger,
  AttributeKey,
  State,
  ClasspathDep,
  ProjectRef,
  IntegrationTest,
  Inc,
  Value,
  Tags,
  KeyRanks
}

import bloop.integrations.sbt.internal.ProjectUtils

import ch.epfl.scala.bsp4j.{CompileResult => Bsp4jCompileResult}
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.CompileParams

import java.{util => ju}
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.{Future => JFuture}
import java.lang.ref.Reference
import java.lang.ref.SoftReference
import java.util.concurrent.TimeUnit

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import bloop.bloop4j.api.NakedLowLevelBuildClient
import bloop.bloop4j.api.handlers.BuildClientHandlers
import xsbti.compile.AnalysisContents
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.Future
import com.lmax.disruptor.TimeoutException
import bloop.launcher.LauncherStatus
import bloop.integrations.sbt.internal.ProjectClientHandlers
import bloop.integrations.sbt.internal.MultiProjectClientHandlers
import ch.epfl.scala.bsp4j.InitializeBuildResult
import bloop.integrations.sbt.internal.SbtBuildClient
import bloop.bloop4j.BloopStopClientCachingParams
import xsbti.compile.ExternalHooks
import java.util.concurrent.atomic.AtomicReference
import java.nio.file.Path
import java.io.IOException
import sbt.TaskCancellationStrategy
import sbt.RunningTaskEngine
import sbt.internal.util.Signals
import java.util.concurrent.atomic.AtomicBoolean
import ch.epfl.scala.bsp4j.CleanCacheParams

/**
 * Todo list:
 *   1. Support cleaning workspace by sending `buildTarget/clean`
 *   2. Speed up bloopGenerate and cache it
 *   5. Send BSP exit when users run `exit`
 *   6. Show errors properly and pretty print them
 *   7. Integrate with heavy task-based input caching
 *   8. Detect when connection been broken and reinitialize it
 */
object Offloader {

  def bloopAnalysisOut: Def.Initialize[Task[Option[File]]] = Def.task {
    import sbt.io.syntax.fileToRichFile
    val cacheDir = Keys.streams.value.cacheDirectory
    Keys.compileAnalysisFilename.in(Keys.compile).?.value.map(f => cacheDir / f)
  }

  def bloopCompileInputsTask: Def.Initialize[Task[BloopCompileInputs]] = Def.task {
    val config = BloopKeys.bloopGenerate.value
    val logger = Keys.streams.value.log
    val targetName = BloopKeys.bloopTargetName.value
    val reporter = BloopCompileKeys.bloopCompilerReporterInternal.value.get
    val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
    val buildTargetId = ProjectUtils.toBuildTargetIdentifier(baseDirectory, targetName)
    BloopCompileInputs(buildTargetId, config, reporter, logger)
  }

  case class BloopCompileState(
      connState: BloopGateway.ConnectionState,
      client: SbtBuildClient,
      handlersMap: ConcurrentHashMap[BuildTargetIdentifier, ProjectClientHandlers],
      analysisMap: ConcurrentHashMap[BuildTargetIdentifier, JFuture[Option[AnalysisContents]]],
      resultsMap: ConcurrentHashMap[BuildTargetIdentifier, CompileResult],
      ongoingRequestsMap: ConcurrentHashMap[JFuture[_], Boolean],
      executor: ExecutorService
  )

  private val DisableCompilationProperty = "sbt-bloop.offload-compilation.disable"
  def connectToBloopServer(rootBaseDir: Path): Option[BloopGateway.ConnectionState] = {
    if (java.lang.Boolean.getBoolean(DisableCompilationProperty)) None
    else Some(BloopGateway.connectOnTheBackgroundTo(rootBaseDir))
  }

  private val executor = Executors.newCachedThreadPool()
  def initializeCompileState(
      rootBaseDir: Path,
      sbtVersion: String,
      logger: Logger
  ): Option[BloopCompileState] = {
    def reportBloopServerError(
        headlineMsg: String,
        errorStatus: String,
        t: Option[Throwable],
        connState: BloopGateway.ConnectionState
    ): Unit = {
      val msg =
        s"""$headlineMsg The build fallbacks to sbt's built-in compilation.
           |  => Launcher error status is $errorStatus
           |  => Investigate errors and stack traces in ${connState.logFile.toAbsolutePath}
             """.stripMargin
      logger.error(msg)
      connState.logOut.println(s"[error] $msg")
      t.foreach(_.printStackTrace(connState.logOut))
    }

    connectToBloopServer(rootBaseDir).flatMap { connState =>
      val logOut = connState.logOut
      var threwException = false
      val startedRunning = connState.running.future
      val maxDuration = FiniteDuration(60, TimeUnit.SECONDS)
      try Await.result(startedRunning, maxDuration)
      catch {
        case e @ (_: TimeoutException | _: InterruptedException) =>
          threwException = true
          val headlineMsg = "Couldn't connect to Bloop server!"
          val errorStatus = connState.exitStatus.get().map(_.toString()).getOrElse("unknown")
          reportBloopServerError(headlineMsg, errorStatus, Some(e), connState)
      }

      val handlers = new ConcurrentHashMap[BuildTargetIdentifier, ProjectClientHandlers]()
      val analysis =
        new ConcurrentHashMap[BuildTargetIdentifier, JFuture[Option[AnalysisContents]]]()
      val results = new ConcurrentHashMap[BuildTargetIdentifier, CompileResult]()
      val requests = new ConcurrentHashMap[JFuture[_], Boolean]()

      connState.exitStatus.get() match {
        case Some(status) if !threwException =>
          status match {
            case LauncherStatus.SuccessfulRun =>
              val headlineMsg = "Bloop launcher exited!"
              reportBloopServerError(headlineMsg, status.toString, None, connState)
            case _ =>
              val headlineMsg = "Couldn't connect to Bloop server!"
              reportBloopServerError(headlineMsg, status.toString, None, connState)
          }
          None
        case _ =>
          val client = new SbtBuildClient(
            connState.baseDir,
            connState.clientIn,
            connState.clientOut,
            new MultiProjectClientHandlers(logger, handlers),
            Some(executor)
          )

          initializeBloopClient(sbtVersion, client, connState, logger).map(
            _ =>
              BloopCompileState(connState, client, handlers, analysis, results, requests, executor)
          )
      }
    }
  }

  def initializeBloopClient(
      sbtVersion: String,
      client: SbtBuildClient,
      connState: BloopGateway.ConnectionState,
      logger: Logger
  ): Option[Unit] = {
    import connState.{logOut, logFile}
    val initializedFuture = client.initializeAsSbtClient(sbtVersion)
    try {
      val result = initializedFuture.get(15, TimeUnit.SECONDS)
      val clientInfo = s"${result.getDisplayName()} (v${result.getVersion()})"
      logOut.println(s"Initialized BSP v${result.getBspVersion()} session with $clientInfo")
      Some(())
    } catch {
      case e @ (_: TimeoutException | _: InterruptedException) =>
        val msg =
          s"""Failed to initialize Bloop session! The build fallbacks to sbt's built-in compilation.
             |  => Investigate errors and stack traces in ${connState.logFile.toAbsolutePath}
             """.stripMargin
        logger.error(msg)
        logOut.println(s"[error] $msg")
        e.printStackTrace(logOut)
        None
    }
  }

  def bloopCompileStateAtBootTimeTask: Def.Initialize[AtomicReference[BloopCompileState]] = {
    Def.setting {
      val logger = Keys.sLog.value
      val sbtVersion = Keys.sbtVersion.value
      val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()
      new AtomicReference(initializeCompileState(rootBaseDir, sbtVersion, logger).orNull)
    }
  }

  def bloopCompileStateTask: Def.Initialize[Task[Option[BloopCompileState]]] = Def.task {
    val logger = Keys.sLog.value
    val sbtVersion = Keys.sbtVersion.value
    val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()
    val globalStateRef = BloopCompileKeys.bloopCompileStateAtBootTimeInternal.value
    globalStateRef.synchronized {
      Option(globalStateRef.get()).flatMap { state =>
        if (!state.connState.isSuspended.get()) Some(state)
        else {
          try {
            // Close launcher streams from suspended session which will stop lsp4j
            state.connState.closeLauncherStreams()
          } catch { case _: IOException => () }

          // Initialize a new compilation state after the suspension and then publish it globally
          initializeCompileState(rootBaseDir, sbtVersion, logger).map { newState =>
            globalStateRef.set(newState)
            newState
          }
        }
      }
    }
  }

  type SessionKey = Option[sbt.Exec]
  @volatile private[this] var previousSessionKey: SessionKey = None
  @volatile private[this] var forceNewSession: Boolean = false

  case class BloopSession(
      requestId: String,
      state: BloopCompileState
  )

  def bloopSessionTaskDontCallDirectly: Def.Initialize[Task[BloopSession]] = Def.task {
    val bloopSessionState = BloopCompileKeys.bloopCompileStateInternal.value.getOrElse(
      throw new IllegalStateException(
        s"Fatal programming error: bloop session task state is accessed even though the compile state is empty, please report this error upstream."
      )
    )

    val sessionKey = Keys.state.value.history.executed.headOption
    val compileRequestId = sessionKey.hashCode.toString

    // We synchronize just out of mistrust on sbt... should only be run once per command execution
    previousSessionKey.synchronized {
      if (forceNewSession || sessionKey != previousSessionKey) {
        forceNewSession = false
        previousSessionKey match {
          case None => ()
          case previousSessionKey @ Some(_) =>
            bloopSessionState.client.stopClientCaching(
              new BloopStopClientCachingParams(previousSessionKey.hashCode.toString)
            )
        }

        bloopSessionState.handlersMap.clear()
        bloopSessionState.analysisMap.clear()
        bloopSessionState.resultsMap.clear()
        previousSessionKey = sessionKey
      }
    }

    BloopSession(compileRequestId, bloopSessionState)
  }

  def bloopWatchBeforeCommandTask: Def.Initialize[() => Unit] = Def.setting { () =>
    forceNewSession = true
  }

  def bloopOffloadCompilationTask: Def.Initialize[Task[CompileResult]] = Def.taskDyn {
    val state = Keys.state.value
    val logger = Keys.streams.value.log
    val scopedKey = Keys.resolvedScoped.value
    val bloopSession = BloopCompileKeys.bloopSessionInternal.value
    val bloopState = bloopSession.state

    val targetName = BloopKeys.bloopTargetName.value
    val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
    val buildTargetId = ProjectUtils.toBuildTargetIdentifier(baseDirectory, targetName)

    val alreadyCompiledAnalysis = bloopState.resultsMap.get(buildTargetId)
    if (alreadyCompiledAnalysis != null) {
      ProjectUtils.inlinedTask(alreadyCompiledAnalysis)
    } else {
      Def.taskDyn {
        val compileInputs = BloopCompileKeys.bloopDependencyInputsInternal.value
        val skipCompilation = !BloopDefaults.targetNamesToConfigs.containsKey(targetName)

        if (skipCompilation) {
          Def.task {
            val setup = toMiniSetup(targetName)
            CompileResult.create(Analysis.Empty, setup, false)
          }
        } else {
          // Prepare inputs and session-specific project handlers
          compileInputs.foreach { inputs =>
            bloopState.handlersMap.putIfAbsent(
              inputs.buildTargetId,
              new ProjectClientHandlers(inputs, bloopState.analysisMap, bloopState.executor)
            )
          }

          // Assemble and make compile request to Bloop server
          val params = new CompileParams(ju.Arrays.asList(buildTargetId))
          params.setOriginId(bloopSession.requestId)
          val result = bloopState.client.compile(params)
          bloopState.ongoingRequestsMap.put(result, true)

          val anyScopedKey = scopedKey.asInstanceOf[ScopedKey[Any]]
          waitForResult(targetName, buildTargetId, bloopSession, result, anyScopedKey, logger)
            .andFinally({ bloopState.ongoingRequestsMap.remove(result); () })
          //.apply(markBloopWaitForCompile(_, scopedKey))
        }
      }
    }
  }

  private val showScopedKey = showShortKey(None)
  private def waitForResultRecursively[T](
      futureResult: JFuture[T],
      timeoutMillis: Long,
      taskKeyName: String
  ): Task[Option[T]] = {
    val task0 = sbt.std.TaskExtra.task {
      Option(futureResult.get(timeoutMillis, TimeUnit.MILLISECONDS))
    }

    task0
      .named(taskKeyName)
      .tag(BloopWait)
      .result
      .flatMap {
        case Value(compileResult) => sbt.std.TaskExtra.inlineTask(compileResult)
        case Inc(cause) =>
          cause.directCause match {
            case Some(t) => waitForResultRecursively(futureResult, timeoutMillis, taskKeyName)
            case None => sys.error("unexpected")
          }
      }
  }

  private val BloopWait = sbt.Tags.Tag("bloop-wait")
  private def waitForResult[T](
      targetName: String,
      buildTarget: BuildTargetIdentifier,
      bloopSession: BloopSession,
      futureResult: JFuture[T],
      scopedKey: ScopedKey[Any],
      logger: Logger
  ): Def.Initialize[Task[CompileResult]] = {
    val prettyPrintedKey =
      showScopedKey.show(scopedKey).replace(scopedKey.key.label, "bloopCompile")
    Def.task {
      //logger.info(s"Waiting for result of ${targetName}")
      val result = waitForResultRecursively(futureResult, 10, prettyPrintedKey).value
      val analysisMap = bloopSession.state.analysisMap
      val resultsMap = bloopSession.state.resultsMap
      result.flatMap(_ => Option(analysisMap.get(buildTarget))) match {
        case None =>
          val setup = toMiniSetup(targetName)
          CompileResult.create(Analysis.Empty, setup, false)

        case Some(analysisFuture) =>
          lazy val emptyResult: CompileResult = {
            logger.warn("Compile analysis was empty")
            val setup = toMiniSetup(targetName)
            CompileResult.create(Analysis.Empty, setup, false)
          }

          val maybeAnalysis = analysisFuture.get()
          val compileResult = {
            if (maybeAnalysis == null) emptyResult
            else {
              maybeAnalysis match {
                case Some(contents) =>
                  CompileResult.create(contents.getAnalysis, contents.getMiniSetup, false)
                case None => emptyResult
              }
            }
          }

          resultsMap.putIfAbsent(buildTarget, compileResult)
          compileResult
      }
    }
  }

  private def showShortKey(
      keyNameColor: Option[String]
  ): sbt.Show[ScopedKey[_]] = {
    def displayShort(
        project: sbt.Reference
    ): String = {
      val trailing = " /"
      project match {
        case sbt.BuildRef(_) => "ThisBuild" + trailing
        case ProjectRef(_, x) => x + trailing
        case _ => sbt.Reference.display(project) + trailing
      }
    }
    sbt.Show[ScopedKey[_]](
      key =>
        sbt.Scope.display(
          key.scope,
          sbt.Def.withColor(key.key.label, keyNameColor),
          ref => displayShort(ref)
        )
    )
  }

  private def toMiniSetup(targetName: String): MiniSetup = {
    import bloop.config.Config
    val project = BloopDefaults.targetNamesToConfigs.get(targetName).project
    val scala = project.scala.get
    val output = sbt.internal.inc.CompileOutput(project.classesDir.toFile)
    val classpath = project.classpath.map(p => xsbti.compile.FileHash.create(p.toFile, 0)).toArray
    val scalacOptions = scala.options.toArray
    val javacOptions = project.java.map(_.options.toArray).getOrElse(new Array(0))
    val options = xsbti.compile.MiniOptions.create(classpath, scalacOptions, javacOptions)
    val order = scala.setup.map(_.order) match {
      case Some(Config.Mixed) => CompileOrder.Mixed
      case Some(Config.JavaThenScala) => CompileOrder.JavaThenScala
      case Some(Config.ScalaThenJava) => CompileOrder.ScalaThenJava
      case None => CompileOrder.Mixed
    }
    MiniSetup.create(output, options, scala.version, order, true, new Array(0))
  }

  def markBloopCompileEntrypoint[T](task: Task[T], currentKey: ScopedKey[_]): Task[T] = {
    val newKey = new sbt.ScopedKey(currentKey.scope, BloopKeys.bloopCompileEntrypoint)
    task.copy(info = task.info.set(Keys.taskDefinitionKey, newKey))
  }

  def markBloopCompileProxy[T](task: Task[T], currentKey: sbt.ScopedKey[_]): Task[T] = {
    val newKey = new sbt.ScopedKey(currentKey.scope, BloopKeys.bloopCompileProxy)
    task.copy(info = task.info.set(Keys.taskDefinitionKey, newKey))
  }

  def markBloopWaitForCompile[T](task: Task[T], currentKey: sbt.ScopedKey[_]): Task[T] = {
    val newKey = new sbt.ScopedKey(currentKey.scope, BloopKeys.bloopWaitForCompile)
    task.copy(info = task.info.set(Keys.taskDefinitionKey, newKey))
  }

  private[sbt] lazy val transitiveClasspathDependency = sbt
    .settingKey[Unit](
      "Leaves a breadcrumb that the scoped task has transitive classpath dependencies"
    )
    .withRank(KeyRanks.Invisible)

  private def bloopDependencyInputsTask: Def.Initialize[Task[Seq[BloopCompileInputs]]] = {
    Def.taskDyn {
      val currentProject = Keys.thisProjectRef.value
      val scope = Keys.resolvedScoped.value.scope
      val data = Keys.settingsData.value
      val deps = Keys.buildDependencies.value
      val conf = Keys.classpathConfiguration.?.value

      conf match {
        case Some(conf) =>
          import scala.collection.JavaConverters._
          val sortedDependencyOrder = Classpaths.interSort(currentProject, conf, data, deps)
          val inputsTasks = (new java.util.LinkedHashSet[Task[BloopCompileInputs]]).asScala

          for ((dependency, dependencyConfig) <- sortedDependencyOrder) {
            val configKey = sbt.ConfigKey(dependencyConfig)
            val inputsKey = BloopCompileKeys.bloopCompileInputsInternal in (dependency, configKey)
            inputsKey.get(data).map { inputsTask =>
              inputsTasks += inputsTask
            }
          }

          Def.value((inputsTasks.toList.join).map(_.distinct))
        case None => ProjectUtils.inlinedTask(Nil)
      }
    }
  }

  def bloopCompileIncSetup: Def.Initialize[Task[CompileSetup]] = Def.task {
    val previousSetup = Keys.compileIncSetup.value
    val _ = Keys.classpathConfiguration.?.value
    val bloopState = BloopCompileKeys.bloopCompileStateAtBootTimeInternal.value.get()
    if (bloopState == null) previousSetup
    else {
      val bloopCacheFile = BloopKeys.bloopAnalysisOut.value.getOrElse(previousSetup.cacheFile())
      CompileSetup.create(
        previousSetup.perClasspathEntryLookup(),
        previousSetup.skip(),
        bloopCacheFile,
        previousSetup.cache(),
        previousSetup.incrementalCompilerOptions(),
        previousSetup.reporter(),
        previousSetup.progress(),
        previousSetup.extra()
      )
    }
  }

  private val taskCancelStrategy =
    sbt.settingKey[State => TaskCancellationStrategy]("Experimental task cancellation handler.")
  def bloopTaskCancelStrategy: Def.Initialize[State => TaskCancellationStrategy] = {
    Def.setting { (state: State) =>
      val bloopState = BloopCompileKeys.bloopCompileStateAtBootTimeInternal.value.get()
      val currentStrategy = taskCancelStrategy.value.apply(state)
      new TaskCancellationStrategy {
        type State = currentStrategy.State
        def onTaskEngineStart(canceller: RunningTaskEngine): State = {
          val bloopTaskEngine = new RunningTaskEngine {
            def cancelAndShutdown(): Unit = {
              val cancelledRequests = new mutable.ListBuffer[JFuture[_]]()
              if (bloopState != null) {
                bloopState.ongoingRequestsMap.forEachKey(4, { future =>
                  if (!future.isDone()) {
                    future.cancel(true)
                    cancelledRequests.+=(future)
                  }
                  ()
                })
              }

              canceller.cancelAndShutdown()

              // Guarantee that these requests are removed from the map after cancellation
              cancelledRequests.foreach(request => bloopState.ongoingRequestsMap.remove(request))
            }
          }

          currentStrategy.onTaskEngineStart(bloopTaskEngine)
        }
        def onTaskEngineFinish(state: State): Unit = {
          currentStrategy.onTaskEngineFinish(state)
        }
      }
    }
  }

  private val externalHooks = sbt.taskKey[ExternalHooks]("The external hooks used by zinc.")
  def bloopCompilerExternalHooksTask: Def.Initialize[Task[ExternalHooks]] = Def.taskDyn {
    val externalHooksTask = externalHooks.taskValue
    val bloopState = BloopCompileKeys.bloopCompileStateAtBootTimeInternal.value.get()
    if (bloopState == null) Def.task(externalHooksTask.value)
    else ProjectUtils.inlinedTask(ProjectUtils.emptyExternalHooks)
  }

  def compile: Def.Initialize[Task[CompileAnalysis]] = {
    Def.taskDyn {
      val config = Keys.configuration.value
      // Depend on classpath config to force derive to scope everywhere it's available
      val _ = Keys.classpathConfiguration.value
      val bloopState = BloopCompileKeys.bloopCompileStateAtBootTimeInternal.value.get()
      val compileTask = Keys.compile.taskValue
      if (bloopState == null) Def.task(compileTask.value)
      else Def.task(BloopKeys.bloopCompile.in(config).value.analysis())
    }
  }

  def bloopClean: Def.Initialize[Task[Unit]] = {
    Def.task {
      val bloopState = BloopCompileKeys.bloopCompileStateAtBootTimeInternal.value.get()
      if (bloopState != null) {
        import scala.collection.JavaConverters._
        val targetName = BloopKeys.bloopTargetName.value
        val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
        val buildTargetId = ProjectUtils.toBuildTargetIdentifier(baseDirectory, targetName)
        val cleanParams = new CleanCacheParams(List(buildTargetId).asJava)
        // TODO: Allow this logic to be cancelled!
        bloopState.client.cleanCache(cleanParams).get()
        ()
      }
    }
  }

  def bloopCompileIncremental: Def.Initialize[Task[CompileResult]] = {
    Def.taskDyn {
      val config = Keys.configuration.value
      // Depend on classpath config to force derive to scope everywhere it's available
      val _ = Keys.classpathConfiguration.value
      val bloopState = BloopCompileKeys.bloopCompileStateAtBootTimeInternal.value.get()
      val compileIncrementalTask = Keys.compileIncremental.taskValue
      if (bloopState == null) Def.task(compileIncrementalTask.value)
      else {
        //println(s"IS SUSPENDED ${bloopState.get.connState.suspendedPromise.get()}")
        BloopKeys.bloopCompile.in(config)
      }
    }
  }

  object BloopCompileKeys {
    val bloopCompileStateInternal: TaskKey[Option[BloopCompileState]] = sbt
      .taskKey[Option[BloopCompileState]]("Obtain the compile state for an sbt shell session")
      .withRank(KeyRanks.Invisible)

    // Copy pasted in case we're using a version below 1.3.0
    val watchBeforeCommand: SettingKey[() => Unit] = sbt
      .settingKey[() => Unit]("Function to run prior to running a command in a continuous build.")
      .withRank(KeyRanks.DSetting)

    val bloopCompileStateAtBootTimeInternal: SettingKey[AtomicReference[BloopCompileState]] = sbt
      .settingKey[AtomicReference[BloopCompileState]](
        "Obtain the compile state for an sbt shell session"
      )
      .withRank(KeyRanks.Invisible)

    val bloopSessionInternal: TaskKey[BloopSession] = sbt
      .taskKey[BloopSession]("Obtain the compile session for an sbt command execution")
      .withRank(KeyRanks.Invisible)

    val bloopCompileInputsInternal: TaskKey[BloopCompileInputs] = sbt
      .taskKey[BloopCompileInputs]("Obtain the compile inputs required to offload compilation")
      .withRank(KeyRanks.Invisible)

    val bloopDependencyInputsInternal = sbt
      .taskKey[Seq[BloopCompileInputs]]("Obtain the dependency compile inputs from this target")
      .withRank(KeyRanks.Invisible)

    val bloopCompilerReporterInternal = sbt
      .taskKey[Option[CompileReporter]]("Obtain compiler reporter scoped in sbt compile task")
      .withRank(KeyRanks.Invisible)

    val bloopCompilerExternalHooks = sbt
      .taskKey[ExternalHooks]("Obtain empty external hooks if bloop compilation is enabled")
      .withRank(KeyRanks.Invisible)

    val bloopCleanInternal = sbt
      .taskKey[Unit]("Send a clean request to the BSP server.")
      .withRank(KeyRanks.Invisible)
  }

  private def sbtBloopPosition = sbt.internal.util.SourcePosition.fromEnclosing()

  private val compileReporterKey =
    TaskKey[CompileReporter]("compilerReporter", rank = KeyRanks.DTask)
  private def bloopCompilerReporterTask: Def.Initialize[Task[Option[CompileReporter]]] = Def.task {
    compileReporterKey.in(Keys.compile).?.value
  }

  private lazy val underivedConfigSettings: Seq[Def.Setting[_]] = List(
    BloopCompileKeys.bloopCompilerReporterInternal.set(bloopCompilerReporterTask, sbtBloopPosition)
  )

  lazy val bloopCompileProjectSettings: Seq[Def.Setting[_]] = List(
    // Necessary because running `clean` doesn't to `compile:clean` in sbt 1.3.0+
    Keys.clean
      .set(Keys.clean.dependsOn(BloopCompileKeys.bloopCleanInternal.in(Compile)), sbtBloopPosition)
  )

  lazy val bloopCompileConfigSettings: Seq[Def.Setting[_]] = underivedConfigSettings ++ List(
    //Keys.compile.set(compile, sbtBloopPosition),
    Keys.compileIncSetup.set(bloopCompileIncSetup, sbtBloopPosition),
    Keys.compileIncremental.set(bloopCompileIncremental, sbtBloopPosition),
    BloopCompileKeys.bloopCleanInternal.set(bloopClean, sbtBloopPosition),
    BloopKeys.bloopCompile.set(Offloader.bloopOffloadCompilationTask, sbtBloopPosition),
    BloopCompileKeys.bloopCompilerExternalHooks
      .set(bloopCompilerExternalHooksTask, sbtBloopPosition),
    BloopCompileKeys.bloopCompileInputsInternal.set(bloopCompileInputsTask, sbtBloopPosition),
    BloopCompileKeys.bloopDependencyInputsInternal.set(bloopDependencyInputsTask, sbtBloopPosition)
  ).map(Def.derive(_, allowDynamic = true))

  private val LimitAllPattern = "Limit all to (\\d+)".r
  lazy val bloopCompileGlobalSettings: Seq[Def.Setting[_]] = List(
    taskCancelStrategy.set(bloopTaskCancelStrategy, sbtBloopPosition),
    BloopCompileKeys.watchBeforeCommand.set(bloopWatchBeforeCommandTask, sbtBloopPosition),
    BloopCompileKeys.bloopCompileStateInternal.set(bloopCompileStateTask, sbtBloopPosition),
    BloopCompileKeys.bloopCompileStateAtBootTimeInternal
      .set(bloopCompileStateAtBootTimeTask, sbtBloopPosition),
    BloopCompileKeys.bloopSessionInternal.set(bloopSessionTaskDontCallDirectly, sbtBloopPosition),
    Keys.concurrentRestrictions := {
      val currentRestrictions = Keys.concurrentRestrictions.value
      val elevatedRestrictions = currentRestrictions.map { restriction =>
        restriction.toString match {
          case LimitAllPattern(n) =>
            val allCores = Integer.parseInt(n)
            Tags.limitAll(allCores + 2)
          case _ => restriction
        }
      }
      elevatedRestrictions ++ List(Tags.limit(BloopWait, 1))
    }
  )

}
