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
import ch.epfl.scala.bsp4j.BloopCompileReport

object SbtBspClient {
  private[bloop] val executor = Executors.newCachedThreadPool()

  type CompileAnalysisMap =
    ConcurrentHashMap[BuildTargetIdentifier, JFuture[Option[AnalysisContents]]]
  val compileAnalysisMapPerRequest = new ConcurrentHashMap[String, CompileAnalysisMap]()

  private[bloop] var cachedBloopBuildClient: Option[NakedLowLevelBuildClient[BuildClientHandlers]] =
    None
  private[bloop] val failedToConnect = new java.util.concurrent.atomic.AtomicBoolean(false)
  def initializeConnection(
      restartLauncher: Boolean,
      baseDir: Path,
      logger: Logger,
      reporter: CompileReporter
  ): Option[NakedLowLevelBuildClient[BuildClientHandlers]] = {
    cachedBloopBuildClient.synchronized {
      val bloopBuildClient = cachedBloopBuildClient match {
        case Some(buildClient) if !restartLauncher => Some(buildClient)
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

          /*
          val launcherThread = new Thread {
            override def run(): Unit = {
              import bloop.launcher.LauncherStatus
              import bloopgun.internal.build.BloopgunInfo.version
              try launcher.cli(Array(version))
              catch {
                case _: Throwable =>
                  failedToConnect.compareAndSet(false, true)
              }
              ()
            }
          }

          launcherThread.start()
          try {
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
            Some(client)
          } catch {
            case _: Throwable =>
              failedToConnect.compareAndSet(false, true)
              None
          }
           */
          failedToConnect.compareAndSet(false, true)
          None
      }
      cachedBloopBuildClient = bloopBuildClient
      bloopBuildClient
    }
  }
}
