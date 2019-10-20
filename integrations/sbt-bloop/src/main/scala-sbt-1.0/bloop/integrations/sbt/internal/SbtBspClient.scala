package bloop.integrations.sbt.internal

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
  ProjectRef
}

import bloop.bloopgun.core.Shell
import bloop.bloop4j.api.NakedLowLevelBuildClient
import bloop.bloop4j.api.handlers.BuildClientHandlers

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
import bloop.integrations.sbt.BloopCompileInputs

class SbtBspClient(logger: Logger, reporter: CompileReporter) extends BuildClientHandlers {
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
      println(s"diagnostic: $diagnostic")
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
    import SbtBspClient.{
      compileRequestDataMap,
      executor,
      readAndStoreAnalysis,
      compileAnalysisMapPerRequest
    }

    if (params.getStatus() == StatusCode.OK) {
      params.getDataKind() match {
        case TaskDataKind.COMPILE_REPORT =>
          parseAs(params.getData, classOf[CompileReport], logger).foreach { report =>
            val target = report.getTarget()
            val requestId = report.getOriginId()
            val compileData = compileRequestDataMap.get(requestId)
            val inputs = compileData.get(target)
            val futureAnalysis = executor.submit(() => readAndStoreAnalysis(inputs))
            val analysisMap = compileAnalysisMapPerRequest.computeIfAbsent(
              requestId,
              (_: String) => {
                new ConcurrentHashMap[BuildTargetIdentifier, JFuture[AnalysisContents]]()
              }
            )
            analysisMap.put(target, futureAnalysis)
          }
          ()
        case _ => ()
      }
      ()
    }

  }

  override def onBuildTaskProgress(params: TaskProgressParams): Unit = ()
  override def onBuildTargetDidChange(params: DidChangeBuildTarget): Unit = ()

  private def parseAs[T](
      obj: Object,
      clazz: Class[T],
      logger: Logger
  ): Option[T] = {
    Option(obj).flatMap { obj =>
      val json = obj.asInstanceOf[JsonElement]
      scala.util.Try(gson.fromJson[T](json, clazz)) match {
        case scala.util.Success(value) => Some(value)
        case scala.util.Failure(t) =>
          logger.error(s"Unexpected error parsing ${clazz}: ${t.getMessage}")
          logger.trace(t)
          None
      }
    }
  }
}

object SbtBspClient {
  private val executor = Executors.newCachedThreadPool()

  val compileRequestDataMap =
    new ConcurrentHashMap[String, ju.HashMap[BuildTargetIdentifier, BloopCompileInputs]]()

  type CompileAnalysisMap = ConcurrentHashMap[BuildTargetIdentifier, JFuture[AnalysisContents]]
  val compileAnalysisMapPerRequest = new ConcurrentHashMap[String, CompileAnalysisMap]()

  private var cachedBloopBuildClient: Option[NakedLowLevelBuildClient] = None
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

          val handlers = new SbtBspClient(logger, reporter)
          val client = new NakedLowLevelBuildClient(
            "sbt",
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

  // TODO return optional?
  private[SbtBspClient] def readAndStoreAnalysis(inputs: BloopCompileInputs): AnalysisContents = {
    try {
      //val analysisOut = inputs.analysisOut //setup.cacheFile()
      val analysisOut = inputs.config.config.project.scala.flatMap(_.analysis).get.toFile
      val store = Utils.bloopStaticCacheStore(analysisOut)
      store.forceAnalysisRead //.getAnalysis
    } catch {
      case NonFatal(t) =>
        t.printStackTrace()
        throw t
    }
  }
}
