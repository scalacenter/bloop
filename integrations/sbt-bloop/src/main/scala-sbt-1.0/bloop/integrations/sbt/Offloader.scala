package bloop.integrations.sbt

import xsbti.compile.MiniSetup
import xsbti.compile.{Setup => CompileSetup}
import xsbti.{Reporter => CompileReporter}
import xsbti.compile.CompileAnalysis

import sbt.internal.inc.LoggedReporter
import sbt.internal.inc.Analysis
import sbt.{Def, Task, TaskKey, Compile, Test, Keys, File, Classpaths, Logger}
import sbt.internal.inc.FileAnalysisStore
import xsbti.compile.AnalysisStore
import sbt.std.TaskExtra

import bloop.bloopgun.core.Shell
import bloop.bloop4j.api.NakedLowLevelBuildClient
import bloop.bloop4j.api.handlers.BuildClientHandlers
import bloop.integrations.sbt.internal.SbtBspReporter

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

object Offloader {
  private var cachedBloopBuildClient: Option[NakedLowLevelBuildClient] = None

  val compileCounterId = new AtomicInteger(0)
  final lazy val bloopOffloadCompilationTask: Def.Initialize[Task[CompileAnalysis]] = Def.task {
    val globalLogger = Keys.sLog.value
    val maxErrors = Keys.maxErrors.in(Keys.compile).value
    val sourcePosMapper = foldMappers(Keys.sourcePositionMappers.in(Keys.compile).value)
    val reporter = new LoggedReporter(maxErrors, globalLogger, sourcePosMapper)

    val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()
    val client = initializeConnection(restartLauncher = false, rootBaseDir, globalLogger, reporter)
    val compileInputs = dependencyBloopCompileInputs.value
    val indexedCompileInputs = new ju.HashMap[BuildTargetIdentifier, BloopCompileInputs]()
    compileInputs.foreach(in => indexedCompileInputs.put(toBuildTargetIdentifier(in.config), in))

    val compileRequestId = compileCounterId.addAndGet(1).toString
    val selfCompileInputs = compileInputs.head
    val targetId = toBuildTargetIdentifier(selfCompileInputs.config)
    val params = new CompileParams(ju.Arrays.asList(targetId))
    params.setOriginId(compileRequestId)
    compileRequestDataMap.put(compileRequestId, indexedCompileInputs)
    val result = client.compile(params).get()

    val analysisFutures = compileAnalysisMapPerRequest.get(compileRequestId)
    import scala.collection.JavaConverters._
    analysisFutures.asScala.valuesIterator.foreach(_.get())
    val selfCompileAnalysis = analysisFutures.get(targetId)
    if (selfCompileAnalysis == null) {
      globalLogger.warn("Compile analysis was empty")
      Analysis.Empty
    } else {
      selfCompileAnalysis.get()
    }
  }

  private def toBuildTargetIdentifier(bloopConfig: BloopProjectConfig): BuildTargetIdentifier = {
    val config = bloopConfig.config
    val targetName = config.project.name
    val targetUri = toURI(config.project.directory, targetName).toString
    new BuildTargetIdentifier(targetUri)
  }

  final lazy val bloopInitializeConnection: Def.Initialize[Unit] = Def.setting {
    val globalLogger = Keys.sLog.value

    val maxErrors = Keys.maxErrors.in(Keys.compile).value
    //val sourcePosMapper = foldMappers(Keys.sourcePositionMappers.in(Keys.compile).value)
    val reporter = new LoggedReporter(maxErrors, globalLogger, identity)

    val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()

    val thread = new Thread {
      override def run(): Unit = {
        initializeConnection(restartLauncher = false, rootBaseDir, globalLogger, reporter)
        ()
      }
    }

    thread.run()
  }

  private def foldMappers[A](mappers: Seq[A => Option[A]]) = {
    mappers.foldRight((p: A) => p) { (mapper, mappers) => p =>
      mapper(p).getOrElse(mappers(p))
    }
  }

  private val executor = Executors.newCachedThreadPool()
  private def toURI(projectBaseDir: Path, id: String): URI = {
    // This is the "idiomatic" way of adding a query to a URI in Java
    val existingUri = projectBaseDir.toUri
    new URI(
      existingUri.getScheme,
      existingUri.getUserInfo,
      existingUri.getHost,
      existingUri.getPort,
      existingUri.getPath,
      s"id=${id}",
      existingUri.getFragment
    )
  }

  def initializeConnection(
      restartLauncher: Boolean,
      baseDir: Path,
      logger: Logger,
      reporter: CompileReporter
  ): NakedLowLevelBuildClient = {
    cachedBloopBuildClient.synchronized {
      val bloopBuildClient = cachedBloopBuildClient match {
        case Some(buildClient) if !restartLauncher => buildClient
        case _ =>
          import scala.concurrent.Promise
          val firstPipe = Pipe.open()
          val launcherIn = Channels.newInputStream(firstPipe.source())
          val clientOut = Channels.newOutputStream(firstPipe.sink())

          val secondPipe = Pipe.open()
          val clientIn = Channels.newInputStream(secondPipe.source())
          val launcherOut = Channels.newOutputStream(secondPipe.sink())

          val debugOut = new ByteArrayOutputStream()
          val startedServer = Promise[Unit]()
          val launcher = new bloop.launcher.LauncherMain(
            launcherIn,
            launcherOut,
            new PrintStream(debugOut),
            StandardCharsets.UTF_8,
            Shell.default,
            None,
            None,
            startedServer
          )

          val launcherThread = new Thread {
            override def run(): Unit = {
              System.err.println(launcher.cli(Array("1.3.4")))
              logger.error("Disconnected!!!")
            }
          }
          launcherThread.start()
          Await.result(startedServer.future, Duration.Inf)

          val handlers = new SbtBspClientHandlers(logger, reporter)
          val client = new NakedLowLevelBuildClient(
            "sbt-bsp-client",
            "2.0.0-M4",
            baseDir,
            clientIn,
            clientOut,
            handlers,
            None,
            Some(executor)
          )
          client.initialize.get()
          client
      }
      cachedBloopBuildClient = Some(bloopBuildClient)
      bloopBuildClient
    }
  }

  private val compileRequestDataMap =
    new ConcurrentHashMap[String, ju.HashMap[BuildTargetIdentifier, BloopCompileInputs]]()
  type CompileAnalysisMap = ConcurrentHashMap[BuildTargetIdentifier, JFuture[CompileAnalysis]]
  private val compileAnalysisMapPerRequest = new ConcurrentHashMap[String, CompileAnalysisMap]()

  private class SbtBspClientHandlers(logger: Logger, reporter: CompileReporter)
      extends BuildClientHandlers {
    override def onBuildLogMessage(params: LogMessageParams): Unit = {
      val msg = params.getMessage()
      params.getType match {
        case MessageType.INFORMATION => logger.info(msg)
        case MessageType.ERROR => logger.error(msg)
        case MessageType.WARNING => logger.warn(msg)
        case MessageType.LOG => logger.info(msg)
      }
    }

    override def onBuildPublishDiagnostics(params: PublishDiagnosticsParams): Unit = {
      // TODO: Figure out resetting the reporter state
      params.getDiagnostics().forEach { diagnostic =>
        SbtBspReporter.report(diagnostic, params.getTextDocument(), reporter)
      }
    }

    override def onBuildShowMessage(params: ShowMessageParams): Unit = {
      val msg = params.getMessage()
      params.getType match {
        case MessageType.INFORMATION => logger.info(msg)
        case MessageType.ERROR => logger.error(msg)
        case MessageType.WARNING => logger.warn(msg)
        case MessageType.LOG => logger.info(msg)
      }
    }

    override def onBuildTaskStart(params: TaskStartParams): Unit = {
      val msg = params.getMessage()
      if (!msg.startsWith("Start no-op compilation for")) {
        logger.info(params.getMessage())
      }
    }

    override def onBuildTaskFinish(params: TaskFinishParams): Unit = {
      //logger.info(params.getMessage())
      if (params.getStatus() == StatusCode.OK) {
        params.getDataKind() match {
          case TaskDataKind.COMPILE_REPORT =>
            val report = decodeJson(params.getData(), classOf[CompileReport], logger).get
            val target = report.getTarget()
            val requestId = report.getOriginId()
            val compileData = compileRequestDataMap.get(requestId)
            val inputs = compileData.get(target)
            val futureAnalysis = executor.submit(() => readAndStoreAnalysis(inputs))
            val analysisMap = compileAnalysisMapPerRequest.computeIfAbsent(
              requestId,
              (_: String) => {
                new ConcurrentHashMap[BuildTargetIdentifier, JFuture[CompileAnalysis]]()
              }
            )
            analysisMap.put(target, futureAnalysis)
            ()
          case _ => ()
        }
      }
    }

    override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
    override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()

  }

  private def decodeJson[T](obj: AnyRef, cls: java.lang.Class[T], logger: Logger): Option[T] = {
    for {
      data <- Option(obj)
      value <- {
        val gson = new Gson()
        val toParse: JsonElement = data match {
          case map: LinkedTreeMap[_, _] => gson.toJsonTree(map).getAsJsonObject()
          case obj => obj.asInstanceOf[JsonElement]
        }
        Some(gson.fromJson[T](toParse, cls))
        /*
      } catch {
        case NonFatal(e) =>
          logger.error(s"decode error: $cls")
          e.printStackTrace()
          logger.trace(e)
					None
					*/
      }
    } yield value
  }

  private def readAndStoreAnalysis(inputs: BloopCompileInputs): CompileAnalysis = {
    val analysisOut = inputs.setup.cacheFile()
    val store = bloopStaticCacheStore(analysisOut)
    store.get().get().getAnalysis()
  }

  private val compileReporterKey =
    TaskKey[CompileReporter]("compilerReporter", rank = sbt.KeyRanks.DTask)

  case class BloopCompileInputs(
      config: BloopProjectConfig,
      setup: CompileSetup
  )

  private lazy val dependencyBloopCompileInputs: Def.Initialize[Task[Seq[BloopCompileInputs]]] = {
    Def.taskDyn {
      val currentConf = Keys.configuration.value
      Def.taskDyn {
        val currentProject = Keys.thisProjectRef.value
        val data = Keys.settingsData.value
        val deps = Keys.buildDependencies.value
        val conf = Keys.classpathConfiguration.in(currentConf).value

        import scala.collection.JavaConverters._
        val noTask: Task[Option[BloopProjectConfig]] = TaskExtra.constant(None)
        val sortedDependencyOrder = Classpaths.interSort(currentProject, conf, data, deps)
        val reporterTasks = new mutable.ListBuffer[Task[(String, CompileReporter)]]()
        val bloopGenerateTasks =
          (new java.util.LinkedHashSet[Task[Option[BloopProjectConfig]]]).asScala
        val bloopSetupTasks =
          (new java.util.LinkedHashSet[Task[CompileSetup]]).asScala

        for ((dependency, dependencyConfig) <- sortedDependencyOrder) {
          val configKey = sbt.ConfigKey(dependencyConfig)
          val bloopGenerateKey = BloopKeys.bloopGenerateInternal in (dependency, configKey)
          bloopGenerateKey.get(data).map { bloopGenerateTask =>
            bloopGenerateTasks += bloopGenerateTask
            val bloopSetupKey = Keys.compileIncSetup in (dependency, configKey)
            bloopSetupTasks += bloopSetupKey.get(data).getOrElse {
              throw new IllegalStateException(
                s"Fatal error: `$bloopSetupKey` is not in scope of `$bloopGenerateKey`!"
              )
            }
          }
        }

        val allSetupTasks = bloopSetupTasks.toList.join
        val allBloopConfigurationTasks = bloopGenerateTasks.toList.join.map(_.flatten)
        val allTasks = TaskExtra.multT2Task((allBloopConfigurationTasks, allSetupTasks)).map {
          case (configs, setups) =>
            configs.iterator.zip(setups.iterator).map {
              case (config, setup) => BloopCompileInputs(config, setup)
            }
        }

        Def.task(allTasks.value.toList)
      }
    }
  }

  import sbt.internal.inc.MixedAnalyzingCompiler
  private val analysisCacheField = MixedAnalyzingCompiler.getClass().getDeclaredField("cache")
  analysisCacheField.setAccessible(true)
  type AnalysisCache = mutable.HashMap[File, java.lang.ref.Reference[AnalysisStore]]
  val analysisCache: AnalysisCache =
    analysisCacheField.get(MixedAnalyzingCompiler).asInstanceOf[AnalysisCache]

  private def bloopStaticCacheStore(analysisOut: File): AnalysisStore = {
    val analysisStore = new BloopAnalysisStore(FileAnalysisStore.binary(analysisOut))
    analysisCache.synchronized {
      val current = analysisCache.get(analysisOut).flatMap(ref => Option(ref.get))
      current match {
        case Some(current: BloopAnalysisStore) => current
        case _ => analysisCache.put(analysisOut, new SoftReference(analysisStore)); analysisStore
      }
    }
  }

  private final class BloopAnalysisStore(backing: AnalysisStore) extends AnalysisStore {
    private var lastStore: ju.Optional[AnalysisContents] = ju.Optional.empty()
    def forceGet(): AnalysisContents = {
      backing.get().get()
    }

    override def get(): ju.Optional[AnalysisContents] = synchronized {
      if (!lastStore.isPresent())
        lastStore = backing.get()
      lastStore
    }

    override def set(analysisFile: AnalysisContents): Unit = synchronized {
      backing.set(analysisFile)
      lastStore = ju.Optional.of(analysisFile)
    }
  }
}
