package bloop.bsp

import java.nio.file.Files

import bloop.cli.Commands
import bloop.io.AbsolutePath
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.{
  BuildClientCapabilities,
  InitializeBuildParams,
  InitializedBuildParams
}
import ch.epfl.scala.bsp.endpoints
import monix.execution.{ExecutionModel, Scheduler}
import monix.{eval => me}
import org.langmeta.jsonrpc.{BaseProtocolMessage, Response, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}
import org.scalasbt.ipcsocket.Win32NamedPipeSocket

import scala.concurrent.duration.FiniteDuration

object BspClientTest {
  private final val slf4jLogger = com.typesafe.scalalogging.Logger("test")

  def cleanUpLastResources(cmd: Commands.ValidatedBsp) = {
    cmd match {
      case cmd: Commands.WindowsLocalBsp => ()
      // We delete the socket file created by the BSP communication
      case cmd: Commands.UnixLocalBsp =>
        if (!Files.exists(cmd.socket)) ()
        else Files.delete(cmd.socket)
      case cmd: Commands.TcpBsp => ()
    }
  }

  def setupBspCommand(cmd: Commands.ValidatedBsp,
                      cwd: AbsolutePath,
                      configDir: AbsolutePath): Commands.ValidatedBsp = {
    val common = cmd.cliOptions.common.copy(workingDirectory = cwd.syntax)
    val cliOptions = cmd.cliOptions.copy(configDir = Some(configDir.underlying), common = common)
    cmd match {
      case cmd: Commands.WindowsLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.UnixLocalBsp => cmd.copy(cliOptions = cliOptions)
      case cmd: Commands.TcpBsp => cmd.copy(cliOptions = cliOptions)
    }
  }

  val scheduler: Scheduler = Scheduler(java.util.concurrent.Executors.newFixedThreadPool(4),
                                       ExecutionModel.AlwaysAsyncExecution)
  def runTest[T](cmd: Commands.ValidatedBsp, configDirectory: AbsolutePath)(
      runEndpoints: LanguageClient => me.Task[Either[Response.Error, T]]): Unit = {

    val projectName = cmd.cliOptions.common.workingPath.underlying.getFileName().toString()
    val state = ProjectHelpers.loadTestProject(projectName)
    val runningBspServer = BspServer.run(cmd, state, scheduler).runAsync(scheduler)

    val bspClientExecution = establishClientConnection(cmd).flatMap { socket =>
      val in = socket.getInputStream
      val out = socket.getOutputStream
      val services = Services.empty
      implicit val lsClient = new LanguageClient(out, slf4jLogger)
      val messages = BaseProtocolMessage.fromInputStream(in)
      messages.doOnSubscriptionCancel(() => "I am being cancelled!")
      val lsServer = new LanguageServer(messages, lsClient, services, scheduler, slf4jLogger)
      val runningClientServer = lsServer.startTask.runAsync(scheduler)

      val cwd = configDirectory.getParent
      val initializeServer = endpoints.Build.initialize.request(
        InitializeBuildParams(
          rootUri = cwd.syntax,
          Some(BuildClientCapabilities(List("scala")))
        )
      )

      val requests = for {
        // Delay the task to let the bloop server go live
        initializeResult <- initializeServer.delayExecution(FiniteDuration(1, "s"))
        val _ = endpoints.Build.initialized.notify(InitializedBuildParams())
        otherCalls <- runEndpoints(lsClient)
      } yield runningClientServer

      requests.doOnFinish { _ =>
        me.Task {
          // `Cancelable.cancelAll` doesn't trigger cancellation!
          runningBspServer.cancel()
          runningClientServer.cancel()
          BspServer.closeSocket(cmd, socket)
        }
      }
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    val sendClientRequests = bspClientExecution.runAsync(scheduler)
    val runningClientServer = Await.result(sendClientRequests, FiniteDuration(10, "s"))
    Await.result(runningBspServer, FiniteDuration(10, "s")) // blocks here?????
    Await.result(runningClientServer, FiniteDuration(10, "s"))
    cleanUpLastResources(cmd)
  }

  private def establishClientConnection(cmd: Commands.ValidatedBsp): me.Task[java.net.Socket] = {
    import org.scalasbt.ipcsocket.UnixDomainSocket
    val connectToServer = me.Task {
      println(s"Starting client connection ${System.currentTimeMillis()}")
      cmd match {
        case cmd: Commands.WindowsLocalBsp => new Win32NamedPipeSocket(cmd.pipeName)
        case cmd: Commands.UnixLocalBsp => new UnixDomainSocket(cmd.socket.toAbsolutePath.toString)
        case cmd: Commands.TcpBsp => new java.net.Socket(cmd.host, cmd.port)
      }
    }

    // We delay the start of the client to wait for the bsp server to go live.
    connectToServer.delayExecution(scala.concurrent.duration.FiniteDuration(400, "ms"))
  }
}
