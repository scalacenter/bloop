package bloop.bsp

import java.io.{PipedInputStream, PipedOutputStream, PrintStream}
import java.util.UUID

import bloop.cli.{CliOptions, Commands, CommonOptions}
import bloop.engine.{ExecutionContext, Interpreter, Run, State}
import bloop.io.AbsolutePath
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
  private final val cwd = AbsolutePath(ProjectHelpers.getTestProjectDir("utest"))
  private final val slf4jLogger = com.typesafe.scalalogging.Logger("test")

  private object SuccessfulBspTest extends Exception("Successful bsp test.")

  @Test def TestBSP(): Unit = {
    val bloopIn = new PipedInputStream()
    val bloopOut = new PipedOutputStream()
    val printOut = new PrintStream(bloopOut)
    val loggerName = UUID.randomUUID().toString
    val bspFileLogs = bloop.io.Paths.bloopLogsDir.resolve("bsp.log")
    val bspLogger = BloopLogger.atFile(loggerName, bspFileLogs)
    val defaultCli = CliOptions.default.copy(configDir = Some(cwd.underlying))
    val newCommonOptions = CommonOptions.default.copy(out = printOut, in = bloopIn)

    val clientIn = new PipedInputStream()
    val clientOut = new PipedOutputStream()

    import ExecutionContext.bspScheduler
    val bspServerExecution = {
      bloopIn.connect(clientOut)
      clientIn.connect(bloopOut)
      val cliOptions = defaultCli.copy(common = newCommonOptions)
      val action = Run(Commands.Bsp(cliOptions = cliOptions))
      val state = State.loadStateFor(cwd.resolve(".bloop-config"), newCommonOptions, bspLogger)
      MonixTask(Interpreter.execute(action, state)).materialize.map {
        case scala.util.Success(_) => ()
        case f: scala.util.Failure[_] => System.err.println(f.exception.toString())
      }
    }

    val bspIntegration = {
      implicit val lsClient = new LanguageClient(clientOut, slf4jLogger)
      val services = Services.empty
      val messages = BaseProtocolMessage.fromInputStream(clientIn)
      val lsServer = new LanguageServer(messages, lsClient, services, bspScheduler, slf4jLogger)
      val startLsServer = lsServer.startTask.delayExecution(FiniteDuration(500, "ms"))

      val initializeServer = endpoints.Build.initialize.request(
        InitializeBuildParams(
          rootUri = cwd.syntax,
          Some(BuildClientCapabilities(List("scala")))
        )
      )

      val clientRequests = for {
        // Delay the task to let the bloop server go live
        initializeResult <- initializeServer.delayExecution(FiniteDuration(500, "ms"))
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
