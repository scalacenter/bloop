package bloop.bsp

import java.nio.file.Files

import bloop.cli.{BspProtocol, Commands}
import bloop.engine.ExecutionContext
import bloop.io.AbsolutePath
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.{
  BuildClientCapabilities,
  InitializeBuildParams,
  InitializedBuildParams
}
import ch.epfl.scala.bsp.endpoints
import monix.execution.Cancelable
import monix.{eval => me}
import org.langmeta.jsonrpc.{BaseProtocolMessage, Response, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}

object BspClientTest {
  private final val slf4jLogger = com.typesafe.scalalogging.Logger("test")

  def cleanUpResources(cmd: Commands.ValidatedBsp) = Cancelable { () =>
    cmd match {
      case cmd: Commands.WindowsLocalBsp => ()
      case cmd: Commands.UnixLocalBsp => Files.delete(cmd.socket)
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

  def runTest[T](cmd: Commands.ValidatedBsp, configDirectory: AbsolutePath)(
      runEndpoints: LanguageClient => me.Task[Either[Response.Error, T]]): Unit = {

    val scheduler = ExecutionContext.bspScheduler
    val projectName = cmd.cliOptions.common.workingPath.underlying.getFileName().toString()
    val state = ProjectHelpers.loadTestProject(projectName)
    val runningBspServer = BspServer.run(cmd, state, scheduler).runAsync(scheduler)

    val bspClientExecution = establishClientConnection(cmd).flatMap { socket =>
      val services = Services.empty
      implicit val lsClient = new LanguageClient(socket.getOutputStream, slf4jLogger)
      val messages = BaseProtocolMessage.fromInputStream(socket.getInputStream)
      val lsServer = new LanguageServer(messages, lsClient, services, scheduler, slf4jLogger)
      val runningClientServer = lsServer.startTask.runAsync(scheduler)
      Thread.sleep(200)

      val cwd = configDirectory.getParent
      val initializeServer = endpoints.Build.initialize.request(
        InitializeBuildParams(
          rootUri = cwd.syntax,
          Some(BuildClientCapabilities(List("scala")))
        )
      )

      val clientRequests = for {
        // Delay the task to let the bloop server go live
        initializeResult <- initializeServer
        val _ = endpoints.Build.initialized.notify(InitializedBuildParams())
        otherCalls <- runEndpoints(lsClient)
      } yield otherCalls

      // We cancel the server when we are done with the requests!
      clientRequests.doOnFinish(_ =>
        me.Task.eval {
          socket.close()
          Cancelable.cancelAll(List(runningClientServer, runningBspServer, cleanUpResources(cmd)))
      })
    }

    import scala.concurrent.Await
    import scala.concurrent.duration.FiniteDuration
    val f = bspClientExecution.runAsync(scheduler)
    val d = Await.result(f, FiniteDuration(15, "s"))
    ()
  }

  private def establishClientConnection(cmd: Commands.ValidatedBsp): me.Task[java.net.Socket] = {
    import org.scalasbt.ipcsocket.UnixDomainSocket
    val connectToServer = me.Task {
      cmd match {
        case cmd: Commands.WindowsLocalBsp => throw new UnsupportedOperationException("Windows")
        case cmd: Commands.UnixLocalBsp => new UnixDomainSocket(cmd.socket.toAbsolutePath.toString)
        case cmd: Commands.TcpBsp => throw new UnsupportedOperationException("TCP")
      }
    }

    // We delay the start of the client to wait for the bsp server to go live.
    connectToServer.delayExecution(scala.concurrent.duration.FiniteDuration(1000, "ms"))
  }
}
