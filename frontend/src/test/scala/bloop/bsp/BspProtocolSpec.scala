package bloop.bsp

import java.net.{Socket, URI}

import bloop.engine.State
import bloop.config.Config
import bloop.io.AbsolutePath
import bloop.cli.{BspProtocol, ExitStatus}
import bloop.util.{TestProject, TestUtil}
import bloop.logging.RecordingLogger
import bloop.internal.build.BuildInfo

import java.nio.file.Path

object TcpBspProtocolSpec extends BspProtocolSpec(BspProtocol.Tcp)
object LocalBspProtocolSpec extends BspProtocolSpec(BspProtocol.Local)

class BspProtocolSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  import ch.epfl.scala.bsp

  test("starts a debug session") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-0.6", workspace, logger) { build =>
        val project = build.projectFor("test-project-test")
        val address = build.state.startDebugSession(project, "Foo")

        val uri = URI.create(address.uri)
        val socket = new Socket(uri.getHost, uri.getPort)
        try {
          socket.close()
        } finally {
          socket.close()
        }
      }
    }
  }

  test("dependency sources request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-0-6")
        val rootTest = build.projectFor("cross-test-build-0-6-test")

        def checkDependencySources(project: TestProject): Unit = {
          val dependencySourcesResult = build.state.requestDependencySources(project)
          assert(dependencySourcesResult.items.size == 1)
          val dependencySources = dependencySourcesResult.items.head

          val expectedSources = project.config.resolution.toList.flatMap { res =>
            res.modules.flatMap { m =>
              m.artifacts.iterator
                .filter(a => a.classifier.toList.contains("sources"))
                .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri).value)
                .toList
            }
          }.distinct

          assertNoDiff(
            dependencySources.sources.map(_.value).sorted.mkString(System.lineSeparator()),
            expectedSources.sorted.mkString(System.lineSeparator())
          )
        }

        checkDependencySources(mainProject)
        checkDependencySources(testProject)
        checkDependencySources(mainJsProject)
        checkDependencySources(testJsProject)
        checkDependencySources(rootMain)
        checkDependencySources(rootTest)
      }
    }
  }
}
