package bloop.bsp

import java.io.{PipedInputStream, PipedOutputStream, PrintStream}
import java.util.UUID

import bloop.cli.{CliOptions, Commands}
import bloop.engine.{ExecutionContext, Interpreter, Run}
import bloop.logging.BloopLogger
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.{BuildClientCapabilities, CompileParams, InitializeBuildParams, InitializedBuildParams, WorkspaceBuildTargetsRequest}
import monix.eval.{Task => MonixTask}
import org.junit.Test
import org.langmeta.jsonrpc.{BaseProtocolMessage, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}
import ch.epfl.scala.bsp.endpoints

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

class BspSpec {
  private final val initialState = ProjectHelpers.loadTestProject("utest")
  private final val dummyLogger = com.typesafe.scalalogging.Logger("test")

  private object SuccessfulBspTest extends Exception("Successful bsp test.")

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

    val clientIn = new PipedInputStream()
    val clientOut = new PipedOutputStream()

    import ExecutionContext.bspScheduler
    val bspServerExecution = {
      bloopIn.connect(clientOut)
      clientIn.connect(bloopOut)
      val cliOptions = defaultCli.copy(common = newCommonOptions)
      val action = Run(Commands.Bsp(cliOptions = cliOptions))
      MonixTask(Interpreter.execute(action, state)).materialize.map {
        case scala.util.Success(_) => ()
        case f: scala.util.Failure[_] => System.err.println(f.exception.toString())
      }
    }

    val bspIntegration = {
      implicit val lsClient = new LanguageClient(clientOut, dummyLogger)
      val services = Services.empty
      val messages = BaseProtocolMessage.fromInputStream(clientIn)
      val lsServer = new LanguageServer(messages, lsClient, services, bspScheduler, dummyLogger)
      val startLsServer = lsServer.startTask.delayExecution(FiniteDuration(1, "s"))

      val initializeServer = endpoints.Build.initialize.request(
        InitializeBuildParams(
          rootUri = initialState.build.origin.syntax,
          Some(BuildClientCapabilities(List("scala")))
        )
      )

      val clientRequests = for {
        // Delay the task to let the bloop server go live
        initializeResult <- initializeServer.delayExecution(FiniteDuration(1, "s"))
        val _ = endpoints.Build.initialized.notify(InitializedBuildParams())
        buildTargets <- endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest())
        val targets = buildTargets.getOrElse(sys.error("Invalid targets response.")).targets
        val target = targets.headOption.flatMap(_.id).toList
        compilationResult <- endpoints.BuildTarget.compile.request(CompileParams(target))
      } yield compilationResult.map(cs => throw SuccessfulBspTest)

      MonixTask.zip3(bspServerExecution, startLsServer, clientRequests)
    }

    try Await.result(bspIntegration.runAsync(ExecutionContext.bspScheduler),
                     FiniteDuration(10, "s"))
    catch {
      case SuccessfulBspTest => ()
      case t: Throwable => throw t
    }
  }
}
