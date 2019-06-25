package bloop.bsp
import bloop.cli.BspProtocol
import bloop.util.TestUtil
import bloop.util.TestProject
import bloop.logging.RecordingLogger
import bloop.logging.BspClientLogger
import bloop.cli.ExitStatus
import java.nio.file.Files
import bloop.data.WorkspaceSettings
import io.circe.JsonObject
import io.circe.Json
import bloop.engine.SemanticDBCache

object LocalBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Local)

class BspMetalsClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {

  test("initialize metals client and save settings") {
    TestUtil.withinWorkspace { workspace =>
      val metalsProject = TestProject(workspace, "metals-project", Nil)
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val additionalData = Some(Json.fromFields(Nil))
      val bspState = loadBspState(workspace, projects, logger, "Metals", additionalData) { state =>
        assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
      }
    }
  }

  test("compile with semanticDB") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )
      val projectName = "metals-project"
      val metalsProject = TestProject(workspace, projectName, sources)
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      WorkspaceSettings.write(configDir, WorkspaceSettings(SemanticDBCache.latestRelease))
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val bspState = loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(metalsProject).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        val classpath = compiledState.client.getUniqueClassesDirFor(
          compiledState.build.getProjectFor(projectName).get
        )
        val semanticDBFile =
          classpath.resolve("META-INF/semanticdb/src/main/scala/Foo.scala.semanticdb")
        assert(semanticDBFile.exists)
      }
    }
  }

  test("compile with semanticDB using cached plugin") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )
      val projectName = "metals-project"
      val metalsProject = TestProject(workspace, projectName, sources)
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      WorkspaceSettings.write(configDir, WorkspaceSettings("4.1.11"))
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val bspState = loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(metalsProject).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        val classpath = compiledState.client.getUniqueClassesDirFor(
          compiledState.build.getProjectFor(projectName).get
        )
        val semanticDBFile =
          classpath.resolve("META-INF/semanticdb/src/main/scala/Foo.scala.semanticdb")
        assert(semanticDBFile.exists)
      }
    }
  }

  test("save settings and compile with semanticDB") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )
      val projectName = "metals-project"
      val metalsProject = TestProject(workspace, projectName, sources)
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val bspState = loadBspState(workspace, projects, logger, "Metals") { state =>
        val compiledState = state.compile(metalsProject).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        val classpath = compiledState.client.getUniqueClassesDirFor(
          compiledState.build.getProjectFor(projectName).get
        )
        val semanticDBFile =
          classpath.resolve("META-INF/semanticdb/src/main/scala/Foo.scala.semanticdb")
        assert(semanticDBFile.exists)
      }
    }
  }
}
