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
  ProjectRef
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

/**
 * Todo list:
 *   1. Support cleaning workspace by sending `buildTarget/clean`
 *   2. Speed up bloopGenerate and cache it
 *   5. Send BSP exit when users run `exit`
 *   6. Show errors properly and pretty print them
 *   7. Integrate with heavy task-based input caching
 */
object Offloader {
  lazy val bloopAnalysisOut: Def.Initialize[Task[Option[File]]] = Def.task {
    import sbt.io.syntax.fileToRichFile
    val cacheDir = Keys.streams.value.cacheDirectory
    Keys.compileAnalysisFilename.in(Keys.compile).?.value.map(f => cacheDir / f)
  }

  private val compileReporterKey =
    TaskKey[CompileReporter]("compilerReporter", rank = sbt.KeyRanks.DTask)
  lazy val bloopCompileInputs: Def.Initialize[Task[Option[BloopCompileInputs]]] = Def.task {
    val config = BloopKeys.bloopGenerateInternal.value
    val reporter = compileReporterKey.in(Keys.compile).?.value
    val analysisOut = BloopKeys.bloopAnalysisOut.value
    for {
      c <- config
      r <- reporter
      a <- analysisOut
    } yield BloopCompileInputs(c, r, a)
  }

  lazy val compileOutputsKey: Def.Initialize[Task[Seq[Path]]] = Def.task {
    import sbt._
    import sbt.io.syntax._
    val classFilesGlob = (Keys.classDirectory.value: File).toGlob / ** / "*.class"
    FileTreeView.default.list(classFilesGlob).map(_._1)
  }

  type SessionKey = Option[sbt.Exec]
  @volatile private[this] var previousSessionKey: SessionKey = None
  private[this] val compiledBloopProjects =
    new ConcurrentHashMap[BuildTargetIdentifier, CompileResult]()

  val compile: Def.Initialize[Task[CompileAnalysis]] = {
    Def.task {
      bloopOffloadCompilationTask.value.analysis()
    }
  }

  val compileIncremental: Def.Initialize[Task[CompileResult]] = {
    Def.task {
      bloopOffloadCompilationTask.value
    }
  }

  lazy val bloopOffloadCompilationTask: Def.Initialize[Task[CompileResult]] = Def.taskDyn {
    val state = Keys.state.value
    val logger = Keys.streams.value.log
    val scopedKey = Keys.resolvedScoped.value
    val targetName = BloopKeys.bloopTargetName.value
    val baseDirectory = Keys.baseDirectory.value.toPath.toAbsolutePath
    val buildTargetId = Utils.toBuildTargetIdentifier(baseDirectory, targetName)
    val sessionKey = state.history.executed.headOption
    val compileRequestId = sessionKey.hashCode.toString

    previousSessionKey.synchronized {
      if (sessionKey != previousSessionKey) {
        SbtBspClient.compileRequestDataMap.clear()
        SbtBspClient.compileAnalysisMapPerRequest.clear()
        SbtBspClient.compileRequestDataMap.remove(compileRequestId)
        SbtBspClient.compileAnalysisMapPerRequest.remove(compileRequestId)
        previousSessionKey = sessionKey
        compiledBloopProjects.clear()
      }
    }

    val alreadyCompiledAnalysis = compiledBloopProjects.get(buildTargetId)
    if (alreadyCompiledAnalysis != null) Def.task(alreadyCompiledAnalysis)
    else {
      val compileTask = Def.taskDyn {
        logger.info(s"Bloop is compiling $buildTargetId!")

        val maxErrors = Keys.maxErrors.in(Keys.compile).value
        val mappers = Utils.foldMappers(Keys.sourcePositionMappers.in(Keys.compile).value)
        val reporter = new LoggedReporter(maxErrors, logger, mappers)
        val rootBaseDir = new File(Keys.loadedBuild.value.root).toPath()
        val client = SbtBspClient.initializeConnection(false, rootBaseDir, logger, reporter)

        val compileInputs = dependencyBloopCompileInputs.value
        val currentInputs = compileInputs.head

        val transitiveInputs0 = new ju.HashMap[BuildTargetIdentifier, BloopCompileInputs]()
        val transitiveInputs = Option(
          SbtBspClient.compileRequestDataMap.putIfAbsent(compileRequestId, transitiveInputs0)
        ).getOrElse(transitiveInputs0)

        compileInputs.foreach { in =>
          val transitiveTarget = Utils.toBuildTargetIdentifier(in.config)
          transitiveInputs.synchronized {
            transitiveInputs.put(transitiveTarget, in)
          }
        }

        // Assemble and make compile request to Bloop server
        val params = new CompileParams(ju.Arrays.asList(buildTargetId))
        params.setOriginId(compileRequestId)
        val result = client.compile(params)

        waitForResult(buildTargetId, compileRequestId, currentInputs, result, logger)
          .apply(markBloopWaitForCompile(_, scopedKey))
      }

      compileTask.apply(markBloopCompileEntrypoint(_, scopedKey))
    }
  }

  private def waitForResult(
      buildTarget: BuildTargetIdentifier,
      compileRequestId: String,
      currentInputs: BloopCompileInputs,
      futureResult: JFuture[Bsp4jCompileResult],
      logger: Logger
  ): Def.Initialize[Task[CompileResult]] = {

    def waitForResult(timeoutMillis: Long): Task[Bsp4jCompileResult] = {
      sbt.std.TaskExtra
        .task(futureResult.get(timeoutMillis, TimeUnit.MILLISECONDS))
        .result
        .flatMap {
          case Value(compileResult) => sbt.std.TaskExtra.inlineTask(compileResult)
          case Inc(cause) =>
            println(s"retrying, inc is $cause")
            cause.directCause match {
              case Some(t) => waitForResult(5)
              case None => sys.error("unexpected")
            }
        }
    }

    Def.task {
      val result = waitForResult(5).value
      Option(SbtBspClient.compileAnalysisMapPerRequest.get(compileRequestId)) match {
        case None =>
          val setup = toMiniSetup(currentInputs)
          CompileResult.create(Analysis.Empty, setup, false)

        case Some(analysisFutures) =>
          logger.info("Blocking on analysis futures")
          import scala.collection.JavaConverters._
          val transitiveResults = analysisFutures.asScala.map(kv => kv._1 -> kv._2.get()).map {
            case (key, analysis) =>
              // TODO: Try to return valid result for `hasModified`
              key -> CompileResult.create(analysis.getAnalysis, analysis.getMiniSetup, false)
          }

          transitiveResults.foreach {
            case (buildTarget, result) =>
              compiledBloopProjects.put(buildTarget, result)
          }

          transitiveResults.get(buildTarget).getOrElse {
            logger.warn("Compile analysis was empty")
            val setup = toMiniSetup(currentInputs)
            CompileResult.create(Analysis.Empty, setup, false)
          }
      }
    }
  }

  private def toMiniSetup(inputs: BloopCompileInputs): MiniSetup = {
    import bloop.config.Config
    val project = inputs.config.config.project
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

  def markBloopWaitForCompile[T](task: Task[T], currentKey: sbt.ScopedKey[_]): Task[T] = {
    val newKey = new sbt.ScopedKey(currentKey.scope, BloopKeys.bloopWaitForCompile)
    task.copy(info = task.info.set(Keys.taskDefinitionKey, newKey))
  }

  /*
  val taskDefinitionKey = AttributeKey[sbt.ScopedKey[_]](
    "task-definition-key",
    "Internal: used to map a task back to its ScopedKey.",
    sbt.KeyRanks.Invisible
  )

  val cloneId = new java.util.concurrent.atomic.AtomicInteger(0)
  def randomKey = AttributeKey[sbt.ScopedKey[_]](
    "random-key-" + cloneId.incrementAndGet(),
    "Internal: used to map a task back to its ScopedKey.",
    sbt.KeyRanks.Invisible
  )

  def changeTaskDefinition[T](task: Task[T]): Task[T] = {
    task.info.get(taskDefinitionKey) match {
      case None => task
      case Some(value) =>
        val scoped = new sbt.ScopedKey(value.scope, randomKey)
        task.copy(info = task.info.set(taskDefinitionKey, scoped))
    }
  }

  def cloneTask[T](task: Task[T]): Task[T] = {
    changeTaskDefinition(
      task.copy(
        work = {
          task.work match {
            case sbt.DependsOn(in, deps) => sbt.DependsOn((in), deps)
            case w: sbt.Mapped[Task[T], k] =>
              println(w.in.asInstanceOf[Task[T]].info)
              sbt.Mapped[Task[T], k](changeTaskDefinition(w.in.asInstanceOf[Task[T]]), w.f, w.alist)
            case w: sbt.FlatMapped[t, k] => sbt.FlatMapped[t, k]((w.in), w.f, w.alist)
            case sbt.Join(in, f) => sbt.Join((in), f)
            case sbt.Pure(f, inline) => sbt.Pure((f), inline)
          }
        }
      )
    )
  }
   */

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

  private lazy val dependencyBloopCompileInputs: Def.Initialize[Task[Seq[BloopCompileInputs]]] = {
    Def.taskDyn {
      val currentConf = Keys.configuration.value
      Def.taskDyn {
        val currentProject = Keys.thisProjectRef.value
        val data = Keys.settingsData.value
        val deps = Keys.buildDependencies.value
        val conf = Keys.classpathConfiguration.in(currentConf).value

        import scala.collection.JavaConverters._
        val noTask: Task[Option[BloopCompileInputs]] = TaskExtra.constant(None)
        val sortedDependencyOrder = Classpaths.interSort(currentProject, conf, data, deps)
        val reporterTasks = new mutable.ListBuffer[Task[(String, CompileReporter)]]()
        val bloopGenerateTasks =
          (new java.util.LinkedHashSet[Task[Option[BloopCompileInputs]]]).asScala
        val bloopSetupTasks =
          (new java.util.LinkedHashSet[Task[CompileSetup]]).asScala

        for ((dependency, dependencyConfig) <- sortedDependencyOrder) {
          val configKey = sbt.ConfigKey(dependencyConfig)
          val bloopGenerateKey = BloopKeys.bloopCompileInputsInternal in (dependency, configKey)
          bloopGenerateKey.get(data).map { bloopGenerateTask =>
            bloopGenerateTasks += bloopGenerateTask
          }
        }

        val allTasks = bloopGenerateTasks.toList.join.map(_.flatten)
        Def.task(allTasks.value.toList)
      }
    }
  }

  val bloopExtraGlobalSettings: Seq[Def.Setting[_]] = List(
    //Keys.progressReports += Keys.TaskProgress(OffloadingExecuteProgress)
  )

}
