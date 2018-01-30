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

class BspProtocolSpec {
  private final val cwd = AbsolutePath(ProjectHelpers.getTestProjectDir("utest"))
  private final val configDir = cwd.resolve("bloop-config")
  private final val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  def validateBsp(bspCommand: Commands.Bsp): Commands.ValidatedBsp = {
    Validate.bsp(bspCommand, BspServer.isWindows) match {
      case Run(bsp: Commands.ValidatedBsp, _) => BspClientTest.setupBspCommand(bsp, cwd, configDir)
      case failed => sys.error(s"Command validation failed: ${failed}")
    }
  }

  def createLocalBspCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    val uniqueId = java.util.UUID.randomUUID().toString.take(4)
    val socketFile = tempDir.resolve(s"test-$uniqueId.socket")
    validateBsp(
      Commands.Bsp(
        protocol = BspProtocol.Local,
        socket = Some(socketFile),
        pipeName = Some(s"\\\\.\\pipe\\test-$uniqueId")
      )
    )
  }

  def createTcpBspCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    validateBsp(Commands.Bsp(protocol = BspProtocol.Tcp))
  }

  def testInitialization(cmd: Commands.ValidatedBsp): Unit = {
    // We test the initialization several times to make sure the scheduler doesn't get blocked.
    def test(counter: Int): Unit = {
      if (counter == 0) ()
      else {
        BspClientTest.runTest(cmd, configDir)(c => monix.eval.Task.eval(Right(())))
        test(counter - 1)
      }
    }

    test(10)
  }

  @Test def TestInitializationViaLocal(): Unit = {
    testInitialization(createLocalBspCommand(configDir))
  }

  @Test def TestInitializationViaTcp(): Unit = {
    testInitialization(createTcpBspCommand(configDir))
  }

  def testBuildTargets(bsp: Commands.ValidatedBsp): Unit = {
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest())
    }

    BspClientTest.runTest(bsp, configDir)(c => clientWork(c))
  }

  @Test def TestBuildTargetsViaLocal(): Unit = {
    testBuildTargets(createLocalBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaTcp(): Unit = {
    testBuildTargets(createTcpBspCommand(configDir))
  }
}
