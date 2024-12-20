package bloop.bsp

import bloop.cli.BspProtocol
import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.util.TestProject
import bloop.cli.ExitStatus

object TcpBspLinkSpec extends BspLinkSpec(BspProtocol.Tcp)
object LocalBspLinkSpec extends BspLinkSpec(BspProtocol.Local)

class BspLinkSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {

  test("can link scala-native-04 cross project") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources(s"cross-test-build-scala-native-0.4", workspace, logger) { build =>
        val project: TestProject = build.projectFor("test-projectNative")
        val compiledState =
          build.state.compile(project, arguments = Some(List("--link")))
        assertEquals(compiledState.status, ExitStatus.Ok)
        assert(
          logger
            .getMessagesAt(level = Some("info"))
            .find(_.contains("Generated native binary"))
            .size == 1
        )
      }
    }
  }
  test("can link scala-native-05 cross project") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources(s"cross-test-build-scala-native-0.5", workspace, logger) { build =>
        val project: TestProject = build.projectFor("test-projectNative")
        val compiledState =
          build.state.compile(project, arguments = Some(List("--link")))
        assertEquals(compiledState.status, ExitStatus.Ok)
        assert(
          logger
            .getMessagesAt(level = Some("info"))
            .find(_.contains("Generated native binary"))
            .size == 1
        )
      }
    }
  }

  test("can link scala-native-05 cross project with release flag") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources(s"cross-test-build-scala-native-0.5", workspace, logger) { build =>
        val project: TestProject = build.projectFor("test-projectNative")
        val compiledState =
          build.state.compile(project, arguments = Some(List("--link", "--release")))
        assertEquals(compiledState.status, ExitStatus.Ok)
        assert(
          logger
            .getMessagesAt(level = Some("debug"))
            .find(_.contains("Optimizing (release-fast mode) "))
            .size == 1
        )
      }
    }
  }

  test("can link scalajs cross project") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources(s"cross-test-build-scalajs-1.x", workspace, logger) { build =>
        val project: TestProject = build.projectFor("test-projectJS")
        val compiledState =
          build.state.compile(project, arguments = Some(List("--link")))
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

  test("can link scalajs cross project with release flag") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources(s"cross-test-build-scalajs-1.x", workspace, logger) { build =>
        val project: TestProject = build.projectFor("test-projectJS")
        val compiledState =
          build.state.compile(project, arguments = Some(List("--link", "--release")))
        assertEquals(compiledState.status, ExitStatus.Ok)
        assert(
          logger
            .getMessagesAt(level = Some("debug"))
            .find(_.contains("Optimizer: Batch mode: true"))
            .size == 1
        )
      }
    }
  }
}
