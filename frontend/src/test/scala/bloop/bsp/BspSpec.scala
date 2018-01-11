package bloop.bsp

import java.io.{ByteArrayOutputStream, PipedInputStream, PipedOutputStream, PrintStream}
import java.util.UUID

import bloop.cli.{CliOptions, Commands}
import bloop.engine.{ExecutionContext, Interpreter, Run, State}
import bloop.logging.BloopLogger
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.{
  BuildClientCapabilities,
  InitializeBuildParams,
  InitializeBuildResult,
  InitializedBuildParams
}
import org.junit.Test
import org.langmeta.jsonrpc.{BaseProtocolMessage, Endpoint, JsonRpcClient, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}

import scala.concurrent.Await

class BspSpec {
  private final val initialState = ProjectHelpers.loadTestProject("sbt")
  private final val dummyLogger = com.typesafe.scalalogging.Logger(this.getClass())

  @Test def TestBSP(): Unit = {
    val state0 = initialState
    val bloopIn = new PipedInputStream()
    val bloopOut = new PipedOutputStream()
    val printOut = new PrintStream(bloopOut)
    val loggerName = UUID.randomUUID().toString
    val newLogger = BloopLogger.at(loggerName, printOut, printOut)
    val defaultCli = CliOptions.default
    val newCommonOptions = state0.commonOptions.copy(out = printOut, in = bloopIn)
    val state = state0.copy(logger = newLogger, commonOptions = newCommonOptions)

    val bspServer = new Thread() {
      override def run(): Unit = {
        val cliOptions = defaultCli.copy(common = newCommonOptions)
        val action = Run(Commands.Bsp(cliOptions = cliOptions))
        try Interpreter.execute(action, state)
        catch {
          case t: Throwable => System.err.println(t.toString())
        }
      }
    }

    val bspClient = {
      val clientIn = new PipedInputStream()
      val clientOut = new PipedOutputStream()
      bloopIn.connect(clientOut)
      clientIn.connect(bloopOut)
      bspServer.start()

      implicit val lsClient = new LanguageClient(clientOut, dummyLogger)
      import ch.epfl.scala.bsp.endpoints.Build._
      import monix.eval.{Task => MonixTask}
      val emptyTask = MonixTask { () }
      val firstClientRequest = emptyTask.flatMap { _ =>
        initialize.request(
          InitializeBuildParams(
            rootUri = initialState.build.origin.syntax,
            Some(BuildClientCapabilities(List("scala")))
          )
        )
      }

      val services = Services.empty
      val serverScheduler = monix.execution.Scheduler.Implicits.global
      val messages = BaseProtocolMessage.fromInputStream(clientIn)
      val lsServer = new LanguageServer(messages, lsClient, services, serverScheduler, dummyLogger)
      lsServer.startTask.executeOn(serverScheduler)

      firstClientRequest.flatMap {
        case Right(result) =>
          System.err.println("GOT RESULT")
          initialized.request(InitializedBuildParams())
        case Left(error) => sys.error("Initialization failed")
      }
    }

    try Await.result(bspClient.runAsync(ExecutionContext.bspScheduler),
                     scala.concurrent.duration.Duration("5s"))
    catch {
      case t: Throwable =>
        println(bloopOut.toString)
        println(t.getMessage)
    }
  }
}
