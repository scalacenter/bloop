package bloop.bsp

import bloop.cli.validation.Validate
import bloop.cli.{BspProtocol, Commands}
import bloop.engine.Run
import bloop.io.AbsolutePath
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.{CompileParams, WorkspaceBuildTargetsRequest}
import org.junit.Test
import ch.epfl.scala.bsp.endpoints
import org.langmeta.lsp.LanguageClient

class BspLocalProtocolSpec {
  private final val cwd = AbsolutePath(ProjectHelpers.getTestProjectDir("utest"))
  private final val configDir = cwd.resolve("bloop-config")

  def createBspLocalCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    val uniqueId = java.util.UUID.randomUUID().toString.take(8)
    val socketFile = configDir.resolve(s"test-$uniqueId.socket").underlying
    val bspCommand = Commands.Bsp(
      protocol = BspProtocol.Local,
      socket = Some(socketFile),
      pipeName = Some(s"\\\\.\\pipe\\test-$uniqueId")
    )

    Validate.bsp(bspCommand, BspServer.isWindows) match {
      case Run(bsp: Commands.ValidatedBsp, _) => BspClientTest.setupBspCommand(bsp, cwd, configDir)
      case failed => sys.error(s"Command validation failed: ${failed}")
    }
  }

  @Test def TestInitialization(): Unit = {
    val bspCommand = createBspLocalCommand(configDir)
    BspClientTest.runTest(bspCommand, configDir)(c => monix.eval.Task.eval(Right(())))
  }

  @Test def TestBuildTargets(): Unit = {
    def clientWork(implicit client: LanguageClient) =
      for {
        buildTargets <- endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest())
      } yield buildTargets

    val bspCommand = createBspLocalCommand(configDir)
    BspClientTest.runTest(bspCommand, configDir)(c => clientWork(c))
  }
}
