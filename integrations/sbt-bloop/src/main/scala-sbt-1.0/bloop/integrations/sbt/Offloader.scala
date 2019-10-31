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
  Tags
}

import bloop.integrations.sbt.internal.Utils
import bloop.integrations.sbt.internal.SbtBspClient

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
    val buildTargetId = Utils.toBuildTargetIdentifier(baseDirectory, targetName)
    BloopCompileInputs(buildTargetId, config, reporter, logger)
  }

  type SessionKey = Option[sbt.Exec]
  @volatile private[this] var previousSessionKey: SessionKey = None

  private val randomId = new java.util.Random()
  def bloopExtractionIdTask: Def.Initialize[Long] = Def.setting(randomId.nextLong())

  // Must be used through its attribute key so that this is only run once in a session
  def bloopSessionIdTask: Def.Initialize[Task[String]] = Def.task {
    val sessionKey = Keys.state.value.history.executed.headOption
    val compileRequestId = sessionKey.hashCode.toString
    previousSessionKey.synchronized {
      if (sessionKey != previousSessionKey) {
        cleanUpStateBeforeSession(compileRequestId)
        previousSessionKey = sessionKey
      }
    }
    compileRequestId
  }

  type BloopBuildClient = NakedLowLevelBuildClient[BuildClientHandlers]
  case class BloopClientState(
      client: BloopBuildClient,
      inputs: ConcurrentHashMap[BuildTargetIdentifier, BloopCompileInputs],
      outputs: ConcurrentHashMap[BuildTargetIdentifier, JFuture[Option[AnalysisContents]]],
      executor: ExecutorService
  )

  def bloopSessionState: Def.Initialize[Task[BloopClientState]] = Def.task {
    val originId = BloopCompileKeys.bloopSessionIdInternal.value
    ???
  }

  private[this] val compiledBloopProjects =
    new ConcurrentHashMap[BuildTargetIdentifier, CompileResult]()

  def cleanUpStateBeforeSession(sessionKey: String): Unit = {
    SbtBspClient.compileAnalysisMapPerRequest.clear()
    compiledBloopProjects.clear()
  }

  val BloopWait = sbt.Tags.Tag("bloop-wait")

  def bloopOffloadCompilationTask: Def.Initialize[Task[CompileResult]] = Def.taskDyn {
    val state = Keys.state.value
    val logger = Keys.streams.value.log
    val scopedKey = Keys.resolvedScoped.value

    val targetName = BloopKeys.bloopTargetName.value
    val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
    val buildTargetId = Utils.toBuildTargetIdentifier(baseDirectory, targetName)
    val compileRequestId = BloopCompileKeys.bloopSessionIdInternal.value

    val alreadyCompiledAnalysis = compiledBloopProjects.get(buildTargetId)
    if (alreadyCompiledAnalysis != null) {
      Utils.inlinedTask(alreadyCompiledAnalysis)
    } else {
      val compileTask = Def.taskDyn {
        logger.info(s"Bloop is compiling ${targetName}!")

        val maxErrors = Keys.maxErrors.in(Keys.compile).value
        val mappers = Utils.foldMappers(Keys.sourcePositionMappers.in(Keys.compile).value)
        val reporter = new LoggedReporter(maxErrors, logger, mappers)
        val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()
        val client = SbtBspClient.initializeConnection(false, rootBaseDir, logger, reporter).get

        val compileInputs = BloopCompileKeys.bloopDependencyInputsInternal.value

        val skipCompilation = BloopDefaults.targetNamesToConfigs.containsKey(targetName)
        if (skipCompilation) {
          Def.task {
            val setup = toMiniSetup(targetName)
            CompileResult.create(Analysis.Empty, setup, false)
          }
        } else {
          val selfTransitiveInputs = new ju.HashMap[BuildTargetIdentifier, BloopCompileInputs]()
          compileInputs.foreach(in => selfTransitiveInputs.putIfAbsent(in.buildTargetId, in))

          val actualTransitiveInputs = Option(
            null //SbtBspClient.compileRequestDataMap.putIfAbsent(compileRequestId, selfTransitiveInputs)
          ).getOrElse(selfTransitiveInputs)

          if (actualTransitiveInputs != selfTransitiveInputs) {
            compileInputs.foreach { in =>
              if (!actualTransitiveInputs.containsKey(in.buildTargetId)) {
                actualTransitiveInputs.synchronized {
                  actualTransitiveInputs.put(in.buildTargetId, in)
                }
              }
            }
          }

          // Assemble and make compile request to Bloop server
          val params = new CompileParams(ju.Arrays.asList(buildTargetId))
          params.setOriginId(compileRequestId)
          val result = client.compile(params)

          waitForResult(targetName, buildTargetId, compileRequestId, result, logger)
            .apply(markBloopWaitForCompile(_, scopedKey))
        }
      }

      compileTask //.apply(markBloopCompileEntrypoint(_, scopedKey))
    }
  }

  private def waitForResult[T](
      targetName: String,
      buildTarget: BuildTargetIdentifier,
      compileRequestId: String,
      futureResult: JFuture[T],
      logger: Logger
  ): Def.Initialize[Task[CompileResult]] = {
    def waitForResult(timeoutMillis: Long): Task[T] = {
      val task0 = sbt.std.TaskExtra
        .task(futureResult.get(timeoutMillis, TimeUnit.MILLISECONDS))
        .tag(BloopWait)
      val task = task0.named("hellooooo")
      task.result
        .flatMap {
          case Value(compileResult) => sbt.std.TaskExtra.inlineTask(compileResult)
          case Inc(cause) =>
            cause.directCause match {
              case Some(t) => waitForResult(50)
              case None => sys.error("unexpected")
            }
        }
    }

    Def.task {
      //logger.info(s"Waiting for result of ${targetName}")
      val result = futureResult.get() // waitForResult(50).value
      Option(SbtBspClient.compileAnalysisMapPerRequest.get(compileRequestId)) match {
        case None =>
          val setup = toMiniSetup(targetName)
          CompileResult.create(Analysis.Empty, setup, false)

        case Some(analysisFutures) =>
          //logger.info(s"Blocking on analysis of ${targetName}")
          import scala.collection.JavaConverters._
          val transitiveResults = analysisFutures.asScala.map(kv => kv._1 -> kv._2.get()).collect {
            case (key, Some(analysis)) =>
              // TODO: Return valid result for `hasModified`
              key -> CompileResult.create(analysis.getAnalysis, analysis.getMiniSetup, false)
          }

          //println(transitiveResults.toMap)
          transitiveResults.foreach {
            case (buildTarget, result) =>
              compiledBloopProjects.put(buildTarget, result)
          }

          //logger.info(s"Finished compilation for ${targetName}")
          val result = transitiveResults.get(buildTarget).getOrElse {
            logger.warn("Compile analysis was empty")
            val setup = toMiniSetup(targetName)
            CompileResult.create(Analysis.Empty, setup, false)
          }
          //logger.info(s"Result obtained for ${targetName}")
          result
      }
    }
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

  def markBloopCompileEntrypoint[T](task: Task[T], currentKey: sbt.ScopedKey[_]): Task[T] = {
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

  lazy val bloopInitializeConnection: Def.Initialize[Unit] = Def.setting {
    val globalLogger = Keys.sLog.value
    val maxErrors = Keys.maxErrors.in(Keys.compile).value
    val reporter = new LoggedReporter(maxErrors, globalLogger, identity)

    val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()

    val thread = new Thread {
      override def run(): Unit = {
        SbtBspClient.initializeConnection(
          restartLauncher = false,
          rootBaseDir,
          globalLogger,
          reporter
        )
        ()
      }
    }

    thread.run()
  }

  private def bloopDependencyInputsTask: Def.Initialize[Task[Seq[BloopCompileInputs]]] = {
    Def.taskDyn {
      val currentProject = Keys.thisProjectRef.value
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
        case None => Utils.inlinedTask(Nil)
      }
    }
  }

  private val LimitAllPattern = "Limit all to (\\d+)".r
  val bloopExtraGlobalSettings: Seq[Def.Setting[_]] = List(
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

  def compileIncSetup: Def.Initialize[Task[CompileSetup]] = Def.taskDyn {
    val currentSetup = Keys.compileIncSetup.?.value
    currentSetup match {
      case None => Def.task(sys.error(""))
      case Some(setup) =>
        Def.task {
          if (SbtBspClient.failedToConnect.get()) setup
          else {
            val bloopCacheFile = BloopKeys.bloopAnalysisOut.value.getOrElse(setup.cacheFile())
            CompileSetup.create(
              setup.perClasspathEntryLookup(),
              setup.skip(),
              bloopCacheFile,
              setup.cache(),
              setup.incrementalCompilerOptions(),
              setup.reporter(),
              setup.progress(),
              setup.extra()
            )
          }
        }
    }
  }

  def compile: Def.Initialize[Task[CompileAnalysis]] = {
    Def.taskDyn {
      val config = Keys.configuration.value
      val conf = Keys.classpathConfiguration.?.value
      val compileTask = Keys.compile.taskValue
      conf match {
        case Some(_) =>
          if (SbtBspClient.failedToConnect.get()) Def.task(compileTask.value)
          else Def.task(BloopKeys.bloopCompile.in(config).value.analysis())
        case None => Def.task(BloopKeys.bloopCompile.in(config).value.analysis())
      }
    }
  }

  def compileIncremental: Def.Initialize[Task[CompileResult]] = {
    Def.taskDyn {
      val config = Keys.configuration.value
      val conf = Keys.classpathConfiguration.?.value
      val compileIncrementalTask = Keys.compileIncremental.taskValue
      conf match {
        case Some(_) =>
          if (SbtBspClient.failedToConnect.get()) Def.task(compileIncrementalTask.value)
          else BloopKeys.bloopCompile.in(config)
        case None => BloopKeys.bloopCompile.in(config)
      }
    }
  }

  object BloopCompileKeys {
    val bloopExtractionIdInternal: SettingKey[Long] = sbt
      .settingKey[Long]("Obtain id which represents uniquely an sbt settings evaluation.")
      .withRank(sbt.KeyRanks.Invisible)

    val bloopSessionIdInternal: TaskKey[String] = sbt
      .taskKey[String]("Obtain the session id for an sbt command execution")
      .withRank(sbt.KeyRanks.Invisible)

    val bloopCompileInputsInternal: TaskKey[BloopCompileInputs] = sbt
      .taskKey[BloopCompileInputs]("Obtain the compile inputs required to offload compilation")
      .withRank(sbt.KeyRanks.Invisible)

    val bloopDependencyInputsInternal = sbt
      .taskKey[Seq[BloopCompileInputs]]("Obtain the dependency compile inputs from this target")
      .withRank(sbt.KeyRanks.Invisible)

    val bloopCompilerReporterInternal = sbt
      .taskKey[Option[CompileReporter]]("Obtain compiler reporter scoped in sbt compile task")
      .withRank(sbt.KeyRanks.Invisible)
  }

  private def sbtBloopPosition = sbt.internal.util.SourcePosition.fromEnclosing()

  private val compileReporterKey =
    TaskKey[CompileReporter]("compilerReporter", rank = sbt.KeyRanks.DTask)
  private def bloopCompilerReporterTask: Def.Initialize[Task[Option[CompileReporter]]] = Def.task {
    compileReporterKey.in(Keys.compile).?.value
  }

  private lazy val highPriorityOffloaderSettings: Seq[Def.Setting[_]] = List(
    BloopCompileKeys.bloopCompilerReporterInternal.set(bloopCompilerReporterTask, sbtBloopPosition)
  )

  lazy val offloaderSettings: Seq[Def.Setting[_]] = highPriorityOffloaderSettings ++ List(
    Keys.compile.set(compile, sbtBloopPosition),
    Keys.compileIncremental.set(compileIncremental, sbtBloopPosition),
    BloopKeys.bloopCompile.set(Offloader.bloopOffloadCompilationTask, sbtBloopPosition),
    Keys.compileIncSetup.set(compileIncSetup, sbtBloopPosition),
    BloopCompileKeys.bloopCompileInputsInternal.set(bloopCompileInputsTask, sbtBloopPosition),
    BloopCompileKeys.bloopDependencyInputsInternal.set(bloopDependencyInputsTask, sbtBloopPosition),
    BloopCompileKeys.bloopSessionIdInternal.set(bloopSessionIdTask, sbtBloopPosition),
    BloopCompileKeys.bloopExtractionIdInternal.set(bloopExtractionIdTask, sbtBloopPosition)
  ).map(Def.derive(_, allowDynamic = true))
}
