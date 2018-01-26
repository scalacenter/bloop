package bloop.bsp

import java.nio.file.Files

import bloop.cli.validation.Validate
import bloop.cli.{BspProtocol, Commands}
import bloop.engine.Run
import bloop.io.AbsolutePath
import bloop.tasks.ProjectHelpers
import ch.epfl.`scala`.bsp.schema.WorkspaceBuildTargetsRequest
import org.junit.Test
import ch.epfl.scala.bsp.endpoints
import org.langmeta.lsp.LanguageClient

class BspLocalProtocolSpec {
  private final val cwd = AbsolutePath(ProjectHelpers.getTestProjectDir("utest"))
  private final val configDir = cwd.resolve("bloop-config")
  private final val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  def createBspLocalCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    val uniqueId = java.util.UUID.randomUUID().toString.take(4)
    val socketFile = tempDir.resolve(s"test-$uniqueId.socket")
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
    // We test the initialization several times to make sure the scheduler doesn't get blocked.
    def test(counter: Int): Unit = {
      if (counter == 0) ()
      else {
        val bspCommand = createBspLocalCommand(configDir)
        BspClientTest.runTest(bspCommand, configDir)(c => monix.eval.Task.eval(Right(())))
        test(counter - 1)
      }
    }

    test(5)
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
