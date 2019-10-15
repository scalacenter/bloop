package bloop.bsp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.{ConcurrentHashMap, ExecutionException}

import bloop.engine.{State, Run}
import bloop.cli.{Commands, Validate, CliOptions, BspProtocol}
import bloop.engine.ExecutionContext
import bloop.engine.caches.ResultsCache
import bloop.internal.build.BuildInfo
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, DebugFilter, RecordingLogger, Slf4jAdapter}
import bloop.util.{UUIDUtil, TestUtil}

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import monix.{eval => me}
import sbt.internal.util.MessageOnlyException

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer, Response, Services}
import scala.concurrent.Promise

object BspClientTest extends BspClientTest
trait BspClientTest {
  private implicit val ctx: DebugFilter = DebugFilter.Bsp
  def cleanUpLastResources(cmd: Commands.ValidatedBsp): Unit = {
    cmd match {
      case cmd: Commands.WindowsLocalBsp => ()
      case cmd: Commands.UnixLocalBsp =>
        // We delete the socket file created by the BSP communication
        if (!Files.exists(cmd.socket.underlying)) ()
        else Files.delete(cmd.socket.underlying)
      case cmd: Commands.TcpBsp => ()
    }
  }

  def validateBsp(bspCommand: Commands.Bsp, configDir: AbsolutePath): Commands.ValidatedBsp = {
    val baseDir = AbsolutePath(configDir.underlying.getParent)
    Validate.bsp(bspCommand, bloop.util.CrossPlatform.isWindows) match {
      case Run(bsp: Commands.ValidatedBsp, _) =>
        BspClientTest.setupBspCommand(bsp, baseDir, configDir)
      case failed => sys.error(s"Command validation failed: ${failed}")
    }
  }

  def createLocalBspCommand(
      configDir: AbsolutePath,
      tempDir: Path
  ): Commands.ValidatedBsp = {
    val uniqueId = UUIDUtil.randomUUID.take(4)
    val socketFile = tempDir.resolve(s"test-$uniqueId.socket")
    validateBsp(
      Commands.Bsp(
        protocol = BspProtocol.Local,
        socket = Some(socketFile),
        pipeName = Some(s"\\\\.\\pipe\\test-$uniqueId")
      ),
      configDir
    )
  }

  def createTcpBspCommand(
      configDir: AbsolutePath,
      portNumber: Int = 5101,
      verbose: Boolean = false
  ): Commands.ValidatedBsp = {
    val opts = if (verbose) CliOptions.default.copy(verbose = true) else CliOptions.default
    validateBsp(
      Commands.Bsp(protocol = BspProtocol.Tcp, port = portNumber, cliOptions = opts),
      configDir
    )
  }

  def setupBspCommand(
      cmd: Commands.ValidatedBsp,
      cwd: AbsolutePath,
      configDir: AbsolutePath
  ): Commands.ValidatedBsp = {
    val common = cmd.cliOptions.common.copy(workingDirectory = cwd.syntax)
    val cliOptions = cmd.cliOptions.copy(configDir = Some(configDir.underlying), common = common)
    cmd match {
      case cmd: Commands.WindowsLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.UnixLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.TcpBsp => cmd.copy(cliOptions = cliOptions)
    }
  }

  // We limit the scheduler on purpose so that we don't have any thread leak.
  val defaultScheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution
  )

  type TestLogger = Slf4jAdapter[RecordingLogger]
  def runTest[T](
      cmd: Commands.ValidatedBsp,
      configDirectory: AbsolutePath,
      logger0: BspClientLogger[_],
      customServices: Services => Services = identity[Services],
      allowError: Boolean = false,
      reusePreviousState: Boolean = false,
      addDiagnosticsHandler: Boolean = true,
      userState: Option[State] = None,
      userScheduler: Option[Scheduler] = None
  )(runEndpoints: LanguageClient => me.Task[Either[Response.Error, T]]): Option[T] = {
    val ioScheduler = userScheduler.getOrElse(defaultScheduler)
    val logger = logger0.asVerbose.asInstanceOf[logger0.type]
    // Set an empty results cache and update the state globally
    val state = userState.getOrElse {
      val state0 = TestUtil.loadTestProject(configDirectory.underlying, logger)
      // Return if we plan to reuse it, BSP reloads the state based on the state cache
      if (reusePreviousState) state0
      else {
        val state = state0.copy(results = ResultsCache.emptyForTests)
        State.stateCache.updateBuild(state)
      }
    }

    val configPath = RelativePath(configDirectory.underlying.getFileName)
    val bspServer = BspServer
      .run(cmd, state, configPath, None, None, ExecutionContext.scheduler, ioScheduler)
      .runAsync(ioScheduler)
    val bspClientExecution = establishClientConnection(cmd).flatMap { socket =>
      val in = socket.getInputStream
      val out = socket.getOutputStream

      implicit val lsClient = new LanguageClient(out, logger)
      val messages = BaseProtocolMessage.fromInputStream(in, logger)
      val services =
        customServices(TestUtil.createTestServices(addDiagnosticsHandler, logger))
      val lsServer = new LanguageServer(messages, lsClient, services, ioScheduler, logger)
      val runningClientServer = lsServer.startTask.runAsync(ioScheduler)

      val cwd = configDirectory.underlying.getParent
      val initializeServer = endpoints.Build.initialize.request(
        bsp.InitializeBuildParams(
          "test-bloop-client",
          "1.0.0",
          BuildInfo.bspVersion,
          rootUri = bsp.Uri(cwd.toAbsolutePath.toUri),
          capabilities = bsp.BuildClientCapabilities(List("scala")),
          None
        )
      )

      for {
        // Delay the task to let the bloop server go live
        initializeResult <- initializeServer.delayExecution(FiniteDuration(1, "s"))
        _ = endpoints.Build.initialized.notify(bsp.InitializedBuildParams())
        otherCalls <- runEndpoints(lsClient)
        _ <- endpoints.Build.shutdown.request(bsp.Shutdown())
        _ = endpoints.Build.exit.notify(bsp.Exit())
      } yield {
        socket.close()
        otherCalls match {
          case Right(res) => Some(res)
          case Left(error) if allowError => None
          case Left(error) => throw new MessageOnlyException(s"Received error ${error}!")
        }
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    val bspClient = bspClientExecution.runAsync(ioScheduler)

    try {
      // The timeout for all our bsp tests, no matter what operation they run, is 60s
      val result = Await.result(bspClient, FiniteDuration(60, "s"))
      Await.result(bspServer, FiniteDuration(60, "s"))
      result
    } catch {
      case e: ExecutionException => throw e.getCause
      case t: Throwable => throw t
    } finally {
      cleanUpLastResources(cmd)
    }
  }

  def establishClientConnection(cmd: Commands.ValidatedBsp): me.Task[java.net.Socket] = {
    import bloop.sockets.UnixDomainSocket
    import bloop.sockets.Win32NamedPipeSocket
    val connectToServer = me.Task {
      cmd match {
        case cmd: Commands.TcpBsp => new java.net.Socket(cmd.host, cmd.port)
        case cmd: Commands.UnixLocalBsp => new UnixDomainSocket(cmd.socket.syntax)
        case cmd: Commands.WindowsLocalBsp => new Win32NamedPipeSocket(cmd.pipeName)
      }
    }
    retryBackoff(connectToServer, 3, FiniteDuration(1, "s"))
  }

  // Courtesy of @olafurpg
  def retryBackoff[A](
      source: me.Task[A],
      maxRetries: Int,
      firstDelay: FiniteDuration
  ): me.Task[A] = {
    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          // Recursive call, it's OK as Monix is stack-safe
          retryBackoff(source, maxRetries - 1, firstDelay * 2)
            .delayExecution(firstDelay)
        else me.Task.raiseError(ex)
    }
  }

  def reportIfError[T](
      logger: BspClientLogger[RecordingLogger],
      expectErrors: Boolean = false
  )(thunk: => T): T = {
    try thunk
    catch {
      case t: Throwable if !expectErrors =>
        val logs = logger.underlying.getMessages().map(t => s"${t._1}: ${t._2}")
        val errorMsg = s"BSP test failed with the following logs:\n${logs.mkString("\n")}"
        val insideCI =
          Option(System.getProperty("CI")).orElse(Option(System.getProperty("JENKINS_HOME")))
        if (insideCI.isDefined) System.err.println(errorMsg)
        else {
          val tempFile = Files.createTempFile("bsp", ".log")
          System.err.println(s"Writing bsp diagnostics logs to ${tempFile}")
          Files.write(tempFile, errorMsg.getBytes(StandardCharsets.UTF_8))
        }
        throw t
    }
  }

  def addServicesTest(
      configDir: AbsolutePath,
      compileIteration: () => Int,
      record: (bsp.BuildTargetIdentifier, StringBuilder => StringBuilder) => Unit,
      compileStartPromises: Option[mutable.HashMap[bsp.BuildTargetIdentifier, Promise[Unit]]]
  ): Services => Services = { (s: Services) =>
    s.notification(endpoints.Build.taskStart) { taskStart =>
        taskStart.dataKind match {
          case Some(bsp.TaskDataKind.CompileTask) =>
            val json = taskStart.data.get
            bsp.CompileTask.decodeCompileTask(json.hcursor) match {
              case Left(failure) => ()
              case Right(compileTask) =>
                compileStartPromises.foreach(
                  promises => promises.get(compileTask.target).map(_.trySuccess(()))
                )
                record(
                  compileTask.target,
                  (builder: StringBuilder) => {
                    builder.synchronized {
                      builder.++=(s"#${compileIteration()}: task start ${taskStart.taskId.id}\n")
                      taskStart.message.foreach(msg => builder.++=(s"  -> Msg: ${msg}\n"))
                      taskStart.dataKind.foreach(msg => builder.++=(s"  -> Data kind: ${msg}\n"))
                      builder
                    }
                  }
                )
            }
          case _ => ()
        }
        ()
      }
      .notification(endpoints.Build.taskFinish) { taskFinish =>
        taskFinish.dataKind match {
          case Some(bsp.TaskDataKind.CompileReport) =>
            val json = taskFinish.data.get
            bsp.CompileReport.decodeCompileReport(json.hcursor) match {
              case Left(failure) => ()
              case Right(report) =>
                record(
                  report.target,
                  (builder: StringBuilder) => {
                    builder.synchronized {
                      builder.++=(s"#${compileIteration()}: task finish ${taskFinish.taskId.id}\n")
                      builder.++=(s"  -> errors ${report.errors}, warnings ${report.warnings}\n")
                      report.originId.foreach(originId => builder.++=(s"  -> origin = $originId\n"))
                      taskFinish.message.foreach(msg => builder.++=(s"  -> Msg: ${msg}\n"))
                      taskFinish.dataKind.foreach(msg => builder.++=(s"  -> Data kind: ${msg}\n"))
                      builder
                    }
                  }
                )
            }
          case _ => ()
        }

        ()
      }
      .notification(endpoints.Build.taskProgress) { case p => () }
      .notification(endpoints.Build.publishDiagnostics) {
        case p @ bsp.PublishDiagnosticsParams(tid, btid, originId, diagnostics, reset) =>
          record(
            btid,
            (builder: StringBuilder) => {
              builder.synchronized {
                val pathString = {
                  val baseDir = {
                    // Find out the current working directory of the workspace instead of project
                    var bdir = AbsolutePath(ProjectUris.toPath(btid.uri))
                    val workspaceFileName = configDir.underlying.getParent.getFileName
                    while (!bdir.underlying.endsWith(workspaceFileName)) {
                      bdir = bdir.getParent
                    }
                    bdir
                  }

                  val abs = AbsolutePath(tid.uri.toPath)
                  if (!abs.underlying.startsWith(baseDir.underlying)) abs.toString
                  else abs.toRelative(baseDir).toString
                }

                val canonical = pathString.replace(File.separatorChar, '/')
                val report = diagnostics.map(
                  _.toString
                    .replace("\n", " ")
                    .replace(System.lineSeparator, " ")
                )
                builder
                  .++=(s"#${compileIteration()}: $canonical\n")
                  .++=(s"  -> $report\n")
                  .++=(s"  -> reset = $reset\n")

                originId match {
                  case None => builder
                  case Some(originId) => builder.++=(s"  -> origin = $originId\n")
                }
              }
            }
          )
          ()
      }
  }

  sealed trait BspClientAction
  object BspClientAction {
    case object CompileEmpty extends BspClientAction // Required to check errors when sending no tid
    case class Compile(target: bsp.BuildTargetIdentifier) extends BspClientAction
    case class CreateFile(path: AbsolutePath, contents: String) extends BspClientAction
    case class DeleteFile(path: AbsolutePath) extends BspClientAction
    case class OverwriteFile(path: AbsolutePath, contents: String) extends BspClientAction
  }

  private type Result = Either[Response.Error, bsp.CompileResult]
  def runCompileTest(
      bspCmd: Commands.ValidatedBsp,
      testActions: List[BspClientAction],
      configDir: AbsolutePath,
      expectErrors: Boolean = false,
      userState: Option[State] = None,
      userScheduler: Option[Scheduler] = None
  ): Map[bsp.BuildTargetIdentifier, String] = {
    var compileIteration = 1
    val compilationResults = new StringBuilder()
    val logger = new BspClientLogger(new RecordingLogger)
    val stringifiedDiagnostics = new ConcurrentHashMap[bsp.BuildTargetIdentifier, StringBuilder]()

    def runFromWorkspaceTargets(targets: List[bsp.BuildTarget])(implicit client: LanguageClient) = {
      def compileProject(tid: bsp.BuildTargetIdentifier): Task[Either[String, Unit]] = {
        endpoints.BuildTarget.compile
          .request(bsp.CompileParams(List(tid), None, None))
          .map {
            case Right(r) => compilationResults.++=(s"${r.statusCode}"); Right(())
            case Left(e) =>
              Left(
                s"""Compilation error for request ${e.id}:
                   |${e.error}""".stripMargin
              )
          }
      }

      import scala.collection.mutable
      val createdFiles = mutable.HashSet.empty[AbsolutePath]
      val originalFiles = mutable.HashMap.empty[AbsolutePath, String]
      def runTestAction(action: BspClientAction): Task[Either[String, Unit]] = {
        action match {
          case BspClientAction.CompileEmpty =>
            endpoints.BuildTarget.compile
              .request(bsp.CompileParams(List(), None, None))
              .map {
                case Right(r) =>
                  if (r.statusCode.code == 0) Right(())
                  else {
                    val builder = new StringBuilder()
                    builder.++=(s"Error when compiling no target: ${r}\n")
                    logger.underlying.errors.foreach(error => builder.++=(error))
                    Left(builder.mkString)
                  }
                case Left(e) =>
                  Left(
                    s"""Compilation error for request ${e.id}:
                       |${e.error}""".stripMargin
                  )
              }
          case BspClientAction.Compile(tid) =>
            targets.map(_.id).find(_ == tid) match {
              case Some(tid) =>
                compileProject(tid).map { res =>
                  compileIteration += 1
                  res
                }
              case None => Task.now(Left(s"Missing target ${tid}"))
            }
          case BspClientAction.CreateFile(path, contents) =>
            Task {
              if (path.exists) {
                Left(s"File path $path already exists, use `OverwriteFile` instead of `CreateFile`")
              } else {
                createdFiles.+=(path)
                Files.write(path.underlying, contents.getBytes(StandardCharsets.UTF_8))
                Right(())
              }
            }

          case BspClientAction.DeleteFile(path) =>
            Task {
              if (path.exists) {
                if (createdFiles.contains(path)) {
                  createdFiles.-=(path)
                } else {
                  val originalContents = new String(path.readAllBytes, StandardCharsets.UTF_8)
                  originalFiles.+=(path -> originalContents)
                }

                Files.delete(path.underlying)
                Right(())
              } else {
                Left(s"File path ${path} doesn't exist, we cannot delete it.")
              }
            }

          case BspClientAction.OverwriteFile(path, contents) =>
            Task {
              if (createdFiles.contains(path)) ()
              else {
                val isNotOverwritten = !originalFiles.contains(path)
                if (isNotOverwritten) {
                  val originalContents = new String(path.readAllBytes, StandardCharsets.UTF_8)
                  originalFiles.+=(path -> originalContents)
                }
              }

              Files.write(path.underlying, contents.getBytes(StandardCharsets.UTF_8))
              Right(())
            }
        }
      }

      val finalResult = testActions match {
        case Nil => sys.error("Test actions are empty!")
        case x :: xs =>
          xs.foldLeft(runTestAction(x)) {
            case (previousResult, nextAction) =>
              previousResult.flatMap {
                case Right(()) => runTestAction(nextAction)
                case Left(msg) => Task.now(Left(msg))
              }
          }
      }

      val deleteAllResources = Task {
        createdFiles.foreach { createdFileDuringTest =>
          Files.deleteIfExists(createdFileDuringTest.underlying)
        }

        originalFiles.foreach {
          case (originalFileBeforeTest, originalContents) =>
            Files.write(
              originalFileBeforeTest.underlying,
              originalContents.getBytes(StandardCharsets.UTF_8)
            )
        }
      }

      finalResult.doOnCancel(deleteAllResources).doOnFinish(_ => deleteAllResources).map { res =>
        res.map { _ =>
          targets.map { target =>
            val tid = target.id
            tid -> Option(stringifiedDiagnostics.get(tid)).map(_.mkString).getOrElse("")
          }.toMap
        }
      }
    }

    def addToStringReport(
        btid: bsp.BuildTargetIdentifier,
        add: StringBuilder => StringBuilder
    ): Unit = {
      stringifiedDiagnostics.compute(
        btid,
        (_, builder0) => add(if (builder0 == null) new StringBuilder() else builder0)
      )
      ()
    }

    reportIfError(logger, expectErrors) {
      val runTest = BspClientTest.runTest(
        bspCmd,
        configDir,
        logger,
        addServicesTest(configDir, () => compileIteration, addToStringReport, None),
        addDiagnosticsHandler = false,
        userState = userState,
        userScheduler = userScheduler
      ) { c =>
        implicit val client = c
        endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
          ts match {
            case Right(workspaceTargets) =>
              runFromWorkspaceTargets(workspaceTargets.targets).flatMap {
                case Left(msg) => Task.now(Left(Response.internalError(msg)))
                case Right(res) => Task.now(Right(res))
              }

            case Left(error) =>
              Task.now(Left(Response.internalError(s"Target request failed with $error.")))
          }
        }
      }
      runTest match {
        case Some(res) => res
        case None => Map.empty
      }
    }
  }

  def checkDiagnostics(diagnostics: Map[bsp.BuildTargetIdentifier, String])(
      tid: bsp.BuildTargetIdentifier,
      expected: String
  ): Unit = {
    val obtained = diagnostics.get(tid).getOrElse(sys.error(s"No diagnostics found for ${tid}"))
    TestUtil.assertNoDiff(expected, obtained)
  }
}
