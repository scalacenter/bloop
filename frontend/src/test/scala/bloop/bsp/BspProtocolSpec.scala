package bloop.bsp

import java.nio.file.Files

import bloop.cli.validation.Validate
import bloop.cli.{BspProtocol, Commands}
import bloop.engine.Run
import bloop.io.AbsolutePath
import bloop.tasks.TestUtil
import bloop.logging.{RecordingLogger, Slf4jAdapter}
import ch.epfl.`scala`.bsp.schema.WorkspaceBuildTargetsRequest
import org.junit.Test
import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.bsp.schema.CompileParams
import junit.framework.Assert
import org.langmeta.lsp.LanguageClient

class BspProtocolSpec {
  private final val configDir = AbsolutePath(TestUtil.getBloopConfigDir("utest"))
  private final val cwd = AbsolutePath(TestUtil.getBaseFromConfigDir(configDir.underlying))
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
    val logger = new Slf4jAdapter(new RecordingLogger)
    // We test the initialization several times to make sure the scheduler doesn't get blocked.
    def test(counter: Int): Unit = {
      if (counter == 0) ()
      else {
        BspClientTest.runTest(cmd, configDir, logger)(c => monix.eval.Task.eval(Right(())))
        test(counter - 1)
      }
    }

    test(10)
  }

  def testBuildTargets(bsp: Commands.ValidatedBsp): Unit = {
    val logger = new Slf4jAdapter(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest()).map {
        case Right(workspaceTargets) =>
          Right(Assert.assertEquals(workspaceTargets.targets.size, 8))
        case Left(error) => Left(error)
      }
    }

    BspClientTest.runTest(bsp, configDir, logger)(c => clientWork(c))
  }

  def testCompile(bsp: Commands.ValidatedBsp): Unit = {
    val logger = new Slf4jAdapter(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            // This will fail if `testBuildTargets` fails too, so let's not handle errors.
            val params = CompileParams(List(workspaceTargets.targets.head.id.get))
            endpoints.BuildTarget.compile.request(params)
          case Left(error) => sys.error(s"Target request failed with $error.")
        }
      }
    }

    BspClientTest.runTest(bsp, configDir, logger)(c => clientWork(c))
  }

  @Test def TestInitializationViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testInitialization(createLocalBspCommand(configDir))
  }

  @Test def TestInitializationViaTcp(): Unit = {
    testInitialization(createTcpBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testBuildTargets(createLocalBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaTcp(): Unit = {
    testBuildTargets(createTcpBspCommand(configDir))
  }

  @Test def TestCompileViaLocal(): Unit = {
    if (!BspServer.isWindows) testCompile(createLocalBspCommand(configDir))
  }

  @Test def TestCompileViaTcp(): Unit = {
    testCompile(createTcpBspCommand(configDir))
  }
}
