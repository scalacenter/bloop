package bloop.bsp

import java.nio.file.Files

import bloop.cli.Commands
import bloop.io.AbsolutePath
import bloop.logging.{BspClientLogger, LogContext, RecordingLogger, Slf4jAdapter}
import bloop.tasks.TestUtil
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.endpoints
import monix.execution.{ExecutionModel, Scheduler}
import monix.{eval => me}
import org.scalasbt.ipcsocket.Win32NamedPipeSocket
import sbt.internal.util.MessageOnlyException

import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc.{BaseProtocolMessage, LanguageClient, LanguageServer, Response, Services}

object BspClientTest {
  private implicit val ctx: LogContext = LogContext.Bsp
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
  val scheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution
  )

  def createServices(logger0: BspClientLogger[_]): Services = {
    val logger: bloop.logging.Logger = logger0
    Services
      .empty(logger0)
      .notification(endpoints.Build.showMessage) {
        case bsp.ShowMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
        case bsp.ShowMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
        case bsp.ShowMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
        case bsp.ShowMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
      }
      .notification(endpoints.Build.logMessage) {
        case bsp.LogMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
        case bsp.LogMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
        case bsp.LogMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
        case bsp.LogMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
      }
      .notification(endpoints.Build.publishDiagnostics) {
        case bsp.PublishDiagnosticsParams(uri, _, diagnostics) =>
          diagnostics.foreach { d =>
            d.severity match {
              case Some(bsp.DiagnosticSeverity.Error) => logger.error(d.toString)
              case Some(bsp.DiagnosticSeverity.Warning) => logger.warn(d.toString)
              case Some(bsp.DiagnosticSeverity.Information) => logger.info(d.toString)
              case Some(bsp.DiagnosticSeverity.Hint) => logger.debug(d.toString)
              case None => logger.info(d.toString)
            }
          }
      }
  }

  type TestLogger = Slf4jAdapter[RecordingLogger]
  def runTest[T](
      cmd: Commands.ValidatedBsp,
      configDirectory: AbsolutePath,
      logger: BspClientLogger[_],
      customServices: Services => Services = identity[Services],
      allowError: Boolean = false,
  )(runEndpoints: LanguageClient => me.Task[Either[Response.Error, T]]): Unit = {
    val workingPath = cmd.cliOptions.common.workingPath
    val projectName = workingPath.underlying.getFileName().toString()
    val state = TestUtil.loadTestProject(projectName).copy(logger = logger)

    // Clean all the project results to avoid reusing previous compiles.
    state.results.cleanSuccessful(state.build.projects)
    val configPath = configDirectory.toRelative(workingPath)
    val bspServer = BspServer.run(cmd, state, configPath, scheduler).runAsync(scheduler)

    val bspClientExecution = establishClientConnection(cmd).flatMap { socket =>
      val in = socket.getInputStream
      val out = socket.getOutputStream

      implicit val lsClient = new LanguageClient(out, logger)
      val messages = BaseProtocolMessage.fromInputStream(in, logger)
      val services = customServices(createServices(logger))
      val lsServer = new LanguageServer(messages, lsClient, services, scheduler, logger)
      val runningClientServer = lsServer.startTask.runAsync(scheduler)

      val cwd = TestUtil.getBaseFromConfigDir(configDirectory.underlying)
      val initializeServer = endpoints.Build.initialize.request(
        bsp.InitializeBuildParams(
          rootUri = bsp.Uri(cwd.toAbsolutePath.toUri),
          capabilities = bsp.BuildClientCapabilities(List("scala"), false)
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
        BspServer.closeSocket(cmd, socket)
        otherCalls match {
          case Right(_) => ()
          case Left(error) if allowError => Left(error)
          case Left(error) => throw new MessageOnlyException(s"Received error ${error}!")
        }
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    val bspClient = bspClientExecution.runAsync(scheduler)
    // The timeout for all our bsp tests, no matter what operation they run, is 60s
    Await.result(bspClient, FiniteDuration(60, "s"))
    Await.result(bspServer, FiniteDuration(60, "s"))
    cleanUpLastResources(cmd)
  }

  private def establishClientConnection(cmd: Commands.ValidatedBsp): me.Task[java.net.Socket] = {
    import org.scalasbt.ipcsocket.UnixDomainSocket
    val connectToServer = me.Task {
      cmd match {
        case cmd: Commands.WindowsLocalBsp => new Win32NamedPipeSocket(cmd.pipeName)
        case cmd: Commands.UnixLocalBsp => new UnixDomainSocket(cmd.socket.syntax)
        case cmd: Commands.TcpBsp => new java.net.Socket(cmd.host, cmd.port)
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
}
