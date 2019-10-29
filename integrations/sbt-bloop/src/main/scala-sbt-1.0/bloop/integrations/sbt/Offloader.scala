package bloop.integrations.sbt

import xsbti.compile.MiniSetup
import xsbti.compile.AnalysisStore
import xsbti.compile.CompileAnalysis
import xsbti.{Reporter => CompileReporter}
import xsbti.compile.{Setup => CompileSetup}

import sbt.internal.inc.FileAnalysisStore
import sbt.std.TaskExtra
import sbt.internal.inc.LoggedReporter
import sbt.internal.inc.Analysis
import sbt.{
  Def,
  Task,
  TaskKey,
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
  IntegrationTest
}

import bloop.bloopgun.core.Shell
import bloop.bloop4j.api.NakedLowLevelBuildClient
import bloop.bloop4j.api.handlers.BuildClientHandlers
import bloop.integrations.sbt.internal.SbtBspReporter
import bloop.integrations.sbt.internal.OffloadingExecuteProgress
import bloop.integrations.sbt.internal.Utils
import bloop.integrations.sbt.internal.SbtBspClient

import java.{util => ju}
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.net.URI
import java.nio.file.Files
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.{Future => JFuture}

import ch.epfl.scala.bsp4j.LogMessageParams
import ch.epfl.scala.bsp4j.MessageType
import ch.epfl.scala.bsp4j.PublishDiagnosticsParams
import ch.epfl.scala.bsp4j.ShowMessageParams
import ch.epfl.scala.bsp4j.DidChangeBuildTarget
import ch.epfl.scala.bsp4j.TaskStartParams
import ch.epfl.scala.bsp4j.TaskFinishParams
import ch.epfl.scala.bsp4j.TaskProgressParams
import ch.epfl.scala.bsp4j.CompileParams
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.TaskDataKind
import ch.epfl.scala.bsp4j.CompileReport
import ch.epfl.scala.bsp4j.StatusCode

import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import java.lang.ref.Reference
import java.lang.ref.SoftReference
import sbt.internal.inc.MixedAnalyzingCompiler
import xsbti.compile.AnalysisContents
import java.util.concurrent.Callable
import scala.util.control.NonFatal
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.internal.LinkedTreeMap
import sbt.nio.file.FileTreeView
import sbt.SessionVar
import java.util.concurrent.atomic.AtomicReference
import xsbti.compile.CompileResult
import xsbti.compile.CompileOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import ch.epfl.scala.bsp4j.{CompileResult => Bsp4jCompileResult}
import sbt.Inc
import sbt.Value
import sbt.Tags
import sbt.MessageOnlyException
import java.nio.file.Paths
import scala.util.hashing.MurmurHash3
import scala.concurrent.Promise
import java.util.concurrent.CompletableFuture
import sbt.util.InterfaceUtil

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

  private val compileReporterKey =
    TaskKey[CompileReporter]("compilerReporter", rank = sbt.KeyRanks.DTask)
  def bloopCompileInputs: Def.Initialize[Task[Option[BloopCompileInputs]]] = Def.taskDyn {
    val compileIsNotScopedInIntegrationTest =
      BloopDefaults.productDirectoriesUndeprecatedKey.?.value.isEmpty

    val logger = Keys.streams.value.log
    val targetName = BloopKeys.bloopTargetName.value
    logger.info(s"Compile inputs init for ${targetName}!")

    //val scopedKey = Keys.resolvedScoped.value
    if (compileIsNotScopedInIntegrationTest) Utils.inlinedTask(None)
    else {
      Def.task {
        //val reporter = compileReporterKey.in(Keys.compile).?.value
        val config = BloopKeys.bloopGenerate.value
        val targetName = BloopKeys.bloopTargetName.value
        val analysisOut = BloopKeys.bloopAnalysisOut.value
        val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
        val buildTargetId = Utils.toBuildTargetIdentifier(baseDirectory, targetName)
        for {
          //c <- config
          //r <- reporter
          a <- analysisOut
        } yield BloopCompileInputs(buildTargetId, a)
      }
      //.apply(markBloopCompileProxy(_, scopedKey))
    }
  }

  type SessionKey = Option[sbt.Exec]
  @volatile private[this] var previousSessionKey: SessionKey = None
  private[this] val compiledBloopProjects =
    new ConcurrentHashMap[BuildTargetIdentifier, CompileResult]()

  val BloopWait = sbt.Tags.Tag("bloop-wait")

  def bloopOffloadCompilationTask: Def.Initialize[Task[CompileResult]] = Def.taskDyn {
    val state = Keys.state.value
    val logger = Keys.streams.value.log
    val scopedKey = Keys.resolvedScoped.value
    val targetName = BloopKeys.bloopTargetName.value
    val defaultScalaVersion = Keys.scalaVersion.in(sbt.Global).value
    val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
    val buildTargetId = Utils.toBuildTargetIdentifier(baseDirectory, targetName)
    val sessionKey = state.history.executed.headOption
    val compileRequestId = sessionKey.hashCode.toString
    val compileIsNotScopedInIntegrationTest =
      BloopDefaults.productDirectoriesUndeprecatedKey.?.value.isEmpty

    previousSessionKey.synchronized {
      if (sessionKey != previousSessionKey) {
        //logger.info(s"Preparing for session clearing from ${previousSessionKey} to ${sessionKey}!")
        SbtBspClient.compileRequestDataMap.clear()
        SbtBspClient.compileAnalysisMapPerRequest.clear()
        SbtBspClient.compileRequestDataMap.remove(compileRequestId)
        SbtBspClient.compileAnalysisMapPerRequest.remove(compileRequestId)
        previousSessionKey = sessionKey
        compiledBloopProjects.clear()
      }
    }

    logger.info(s"Bloop dynamic init for ${targetName}!")
    val alreadyCompiledAnalysis = compiledBloopProjects.get(buildTargetId)
    if (compileIsNotScopedInIntegrationTest) {
      // Should never run unless plugins use ScopeFilter, return dummy analysis just in case
      Utils.inlinedTask(emptyCompileResult(targetName, defaultScalaVersion))
    } else if (alreadyCompiledAnalysis != null) {
      Utils.inlinedTask(alreadyCompiledAnalysis)
    } else {
      val compileTask = Def.taskDyn {
        logger.info(s"Bloop is compiling ${targetName}!")

        val maxErrors = Keys.maxErrors.in(Keys.compile).value
        val mappers = Utils.foldMappers(Keys.sourcePositionMappers.in(Keys.compile).value)
        val reporter = new LoggedReporter(maxErrors, logger, mappers)
        val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()
        val client = SbtBspClient.initializeConnection(false, rootBaseDir, logger, reporter).get

        val compileInputs = bloopDependencyCompileInputsInternal.value

        val selfTransitiveInputs = new ju.HashMap[BuildTargetIdentifier, BloopCompileInputs]()
        compileInputs.foreach(in => selfTransitiveInputs.putIfAbsent(in.buildTargetId, in))

        val actualTransitiveInputs = Option(
          SbtBspClient.compileRequestDataMap.putIfAbsent(compileRequestId, selfTransitiveInputs)
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
      val result = waitForResult(50).value
      Option(SbtBspClient.compileAnalysisMapPerRequest.get(compileRequestId)) match {
        case None =>
          val setup = toMiniSetup(targetName)
          CompileResult.create(Analysis.Empty, setup, false)

        case Some(analysisFutures) =>
          //logger.info(s"Blocking on analysis of ${targetName}")
          import scala.collection.JavaConverters._
          val results2 = analysisFutures.asScala.map(kv => kv._1 -> kv._2.get())
          println(results2)
          val transitiveResults = results2.collect {
            case (key, Some(analysis)) =>
              // TODO: Return valid result for `hasModified`
              key -> CompileResult.create(analysis.getAnalysis, analysis.getMiniSetup, false)
          }

          println(transitiveResults.toMap)
          transitiveResults.foreach {
            case (buildTarget, result) =>
              compiledBloopProjects.put(buildTarget, result)
          }

          logger.info(s"Finished compilation for ${targetName}")
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

  private def emptyCompileResult(targetName: String, defaultScalaVersion: String): CompileResult = {
    def emptyMiniSetup: MiniSetup = {
      // Create dummy file whose path tells users this result is dummy
      val outputFile = Paths
        .get(sys.props("user.dir"))
        .resolve(".sbt")
        .resolve("bloop-non-existing-dummy-classes-dir")
        .toFile
      val output = sbt.internal.inc.CompileOutput(outputFile)
      val options = xsbti.compile.MiniOptions.create(new Array(0), new Array(0), new Array(0))
      val order = CompileOrder.Mixed
      MiniSetup.create(output, options, defaultScalaVersion, order, true, new Array(0))
    }

    CompileResult.create(Analysis.Empty, emptyMiniSetup, false)
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

  private def bloopDependencyCompileInputs: Def.Initialize[Task[Seq[BloopCompileInputs]]] = {
    Def.taskDyn {
      val currentProject = Keys.thisProjectRef.value
      val data = Keys.settingsData.value
      val deps = Keys.buildDependencies.value
      val conf = Keys.classpathConfiguration.?.value

      conf match {
        case Some(conf) =>
          import scala.collection.JavaConverters._
          val noTask: Task[Option[BloopCompileInputs]] = TaskExtra.constant(None)
          val sortedDependencyOrder = Classpaths.interSort(currentProject, conf, data, deps)
          val bloopCompileInputsTasks =
            (new java.util.LinkedHashSet[Task[Option[BloopCompileInputs]]]).asScala

          for ((dependency, dependencyConfig) <- sortedDependencyOrder) {
            val configKey = sbt.ConfigKey(dependencyConfig)
            val bloopCompileInputsKey = bloopCompileInputsInternal in (dependency, configKey)
            bloopCompileInputsKey.get(data).map { bloopCompileInputsTask =>
              //println(s"Adding bloop generate task in ${(dependency, configKey)}")
              bloopCompileInputsTasks += bloopCompileInputsTask
            }
          }

          Def.value((bloopCompileInputsTasks.toList.join).map(_.flatten.distinct))
        case None => Utils.inlinedTask(Nil)
      }

    }
  }

  private val LimitAllPattern = "Limit all to (\\d+)".r
  val bloopExtraGlobalSettings: Seq[Def.Setting[_]] = List(
    //Keys.progressReports += Keys.TaskProgress(OffloadingExecuteProgress),
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

  val bloopCompileInputsInternal: TaskKey[Option[BloopCompileInputs]] =
    sbt.TaskKey[Option[BloopCompileInputs]](
      "bloopCompileInputsInternal",
      "Obtain the compile inputs required to offload compilation",
      rank = sbt.KeyRanks.Invisible
    )

  val bloopDependencyCompileInputsInternal: TaskKey[Seq[BloopCompileInputs]] =
    sbt.TaskKey[Seq[BloopCompileInputs]](
      "bloopDependencyCompileInputsInternal",
      "Obtain the dependency compile inputs from this target",
      rank = sbt.KeyRanks.Invisible
    )

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

  private def none[A]: Option[A] = (None: Option[A])
  private def jnone[A]: ju.Optional[A] = InterfaceUtil.toOptional(none[A])
  lazy val offloaderSettings: Seq[Def.Setting[_]] = List(
    Def.derive(Keys.compile.set(compile, sbt.internal.util.NoPosition), allowDynamic = true),
    Def.derive(
      Keys.compileIncremental.set(compileIncremental, sbt.internal.util.NoPosition),
      allowDynamic = true
    ),
    BloopKeys.bloopCompile := Offloader.bloopOffloadCompilationTask.value,
    Keys.compileIncSetup := compileIncSetup.value,
    bloopCompileInputsInternal := bloopCompileInputs.value,
    bloopDependencyCompileInputsInternal := bloopDependencyCompileInputs.value
  )
}
