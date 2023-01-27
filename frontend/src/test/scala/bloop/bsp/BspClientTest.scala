package bloop.bsp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ExecutionException

import scala.collection.mutable
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util._

import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints

import bloop.cli.BspProtocol
import bloop.cli.CliOptions
import bloop.cli.Commands
import bloop.cli.Validate
import bloop.engine.ExecutionContext
import bloop.engine.Run
import bloop.engine.State
import bloop.engine.caches.ResultsCache
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.io.Environment.END_OF_LINE_MATCHER
import bloop.io.RelativePath
import bloop.logging.BspClientLogger
import bloop.logging.RecordingLogger
import bloop.logging.Slf4jAdapter
import bloop.task.Task
import bloop.util.TestUtil
import bloop.util.UUIDUtil

import com.github.plokhotnyuk.jsoniter_scala.core._
import jsonrpc4s._
import monix.execution.ExecutionModel
import monix.execution.Scheduler
import sbt.internal.util.MessageOnlyException

object BspClientTest extends BspClientTest
trait BspClientTest {
  def cleanUpLastResources(cmd: Commands.ValidatedBsp): Unit = {
    cmd match {
      case _: Commands.WindowsLocalBsp => ()
      case cmd: Commands.UnixLocalBsp =>
        // We delete the socket file created by the BSP communication
        if (!Files.exists(cmd.socket.underlying)) ()
        else Files.delete(cmd.socket.underlying)
      case _: Commands.TcpBsp => ()
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
      customServices: BloopRpcServices => BloopRpcServices = identity[BloopRpcServices],
      allowError: Boolean = false,
      reusePreviousState: Boolean = false,
      addDiagnosticsHandler: Boolean = true,
      userState: Option[State] = None,
      userScheduler: Option[Scheduler] = None
  )(runEndpoints: BloopLanguageClient => Task[Either[Response.Error, T]]): Option[T] = {
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

      val lsClient = BloopLanguageClient.fromOutputStream(out, logger)
      val messages = LowLevelMessage
        .fromInputStream(in, logger)
        .map(msg => LowLevelMessage.toMsg(msg))
      val services =
        customServices(TestUtil.createTestServices(addDiagnosticsHandler, logger))
      val lsServer = new BloopLanguageServer(messages, lsClient, services, ioScheduler, logger)

      lsServer.startTask.runToFuture(ioScheduler)

      val cwd = configDirectory.underlying.getParent
      val initializeServer =
        lsClient.request(
          endpoints.Build.initialize,
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
        _ = lsClient.notify(endpoints.Build.initialized, bsp.InitializedBuildParams())
        otherCalls <- runEndpoints(lsClient)
        _ <- lsClient.request(endpoints.Build.shutdown, bsp.Shutdown())
        _ = lsClient.notify(endpoints.Build.exit, bsp.Exit())
      } yield {
        socket.close()
        otherCalls match {
          case Right(res) => Some(res)
          case Left(_) if allowError => None
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

  def establishClientConnection(cmd: Commands.ValidatedBsp): Task[java.net.Socket] = {
    import bloop.sockets.UnixDomainSocket
    import bloop.sockets.Win32NamedPipeSocket
    val connectToServer = Task {
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
      source: Task[A],
      maxRetries: Int,
      firstDelay: FiniteDuration
  ): Task[A] = {
    source.onErrorHandleWith {
      case ex: Exception =>
        if (maxRetries > 0)
          // Recursive call, it's OK as Monix is stack-safe
          retryBackoff(source, maxRetries - 1, firstDelay * 2)
            .delayExecution(firstDelay)
        else Task.raiseError(ex)
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
  ): BloopRpcServices => BloopRpcServices = { (s: BloopRpcServices) =>
    s.notification(endpoints.Build.taskStart) { taskStart =>
      taskStart.dataKind match {
        case Some(bsp.TaskDataKind.CompileTask) =>
          val json = taskStart.data.get
          Try(readFromArray[bsp.CompileTask](json.value)) match {
            case Failure(_) => ()
            case Success(compileTask) =>
              compileStartPromises.foreach(promises =>
                promises.get(compileTask.target).map(_.trySuccess(()))
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
    }.notification(endpoints.Build.taskFinish) { taskFinish =>
      taskFinish.dataKind match {
        case Some(bsp.TaskDataKind.CompileReport) =>
          val json = taskFinish.data.get
          Try(readFromArray[bsp.CompileReport](json.value)) match {
            case Failure(_) => ()
            case Success(report) =>
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
    }.notification(endpoints.Build.taskProgress) { case _ => () }
      .notification(endpoints.Build.publishDiagnostics) {
        case bsp.PublishDiagnosticsParams(tid, btid, originId, diagnostics, reset) =>
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
                  _.copy(source = Some("_"), code = Some("_")).toString
                    .replace("\n", " ")
                    .replace(END_OF_LINE_MATCHER, " ")
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

}
