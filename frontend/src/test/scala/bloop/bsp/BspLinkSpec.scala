package bloop.bsp

import bloop.cli.BspProtocol
import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.data.Project
import bloop.data.Platform
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.engine.State
import bloop.cli.ExitStatus

object TcpBspLinkSpec extends BspLinkSpec(BspProtocol.Tcp)
object LocalBspLinkSpec extends BspLinkSpec(BspProtocol.Local)

class BspLinkSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {

  val state0: State = {
    def setUpScalajs(p: Project): Project = {
      val platform = p.platform match {
        case jsPlatform: Platform.Js =>
          jsPlatform.copy(toolchain = Some(ScalaJsToolchain.apply(this.getClass.getClassLoader)))
        case _ => p.platform
      }
      p.copy(platform = platform)
    }

    val configDir = TestUtil.getBloopConfigDir("cross-test-build-scalajs-1.x")
    val logger = bloop.logging.BloopLogger.default(configDir.toString())
    TestUtil.loadTestProject(configDir, logger, true, _.map(setUpScalajs))
  }

  test("can link scalajs cross project") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources(s"cross-test-build-scalajs-1.x", workspace, logger) { build =>
        val project: TestProject = build.projectFor("test-projectJS")
        val newB = build.copy(state = build.state.copy(state = state0))
        val compiledState =
          newB.state.compile(project, arguments = Some(List("--link")))
        assertEquals(compiledState.status, ExitStatus.Ok)
        assert(
          logger
            .getMessagesAt(level = Some("info"))
            .find(_.contains("Generated JavaScript file"))
            .size == 1
        )
      }
    }
  }
}
