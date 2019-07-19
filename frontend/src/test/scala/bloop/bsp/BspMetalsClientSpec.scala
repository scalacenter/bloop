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
import bloop.internal.build.BuildInfo
import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import monix.execution.Scheduler
import monix.execution.ExecutionModel
import monix.eval.Task
import scala.concurrent.duration.FiniteDuration
import bloop.io.AbsolutePath
import ch.epfl.scala.bsp.endpoints.BuildTarget.scalacOptions

object LocalBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Local)
object TcpBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Tcp)

class BspMetalsClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {

  val testedScalaVersion = "2.12.8"
  val projectName = "metals-project"

  test("initialize metals client and save settings") {
    TestUtil.withinWorkspace { workspace =>
      val metalsProject =
        TestProject(workspace, projectName, Nil, scalaVersion = Some(testedScalaVersion))
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion),
        reapplySettings = false
      )
      val bspState =
        loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) {
          state =>
            assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
            val settings = WorkspaceSettings.fromFile(configDir, logger)
            assert(settings.isDefined && settings.get.semanticDBVersion == semanticdbVersion)
            val scalacOptions = state.scalaOptions(metalsProject)._2.items.head.options
            assert(
              List(
                "-P:semanticdb:failures:warning",
                s"-P:semanticdb:sourceroot:$workspace",
                "-P:semanticdb:synthetics:on",
                "-Xplugin-require:semanticdb",
                "semanticdb-scalac"
              ).forall(opt => scalacOptions.find(_.contains(opt)).isDefined)
            )
        }
    }
  }

  test("do not initialize metals client and save settings with unsupported scala version") {
    TestUtil.withinWorkspace { workspace =>
      val metalsProject =
        TestProject(workspace, projectName, Nil, scalaVersion = Some("2.12.4"))
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List("2.12.8"),
        reapplySettings = false
      )
      val bspState =
        loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) {
          state =>
            val scalacOptions = state.scalaOptions(metalsProject)._2.items.head.options
            assert(scalacOptions.isEmpty)
        }
    }
  }

  test("initialize metals client with existing plugin in workspace") {
    TestUtil.withinWorkspace { workspace =>
      val defaultScalacOptions = List(
        "-P:semanticdb:failures:warning",
        s"-P:semanticdb:sourceroot:$workspace",
        "-P:semanticdb:synthetics:on",
        "-Xplugin-require:semanticdb",
        s"-Xplugin:path-to-plugin/semanticdb-scalac_2.12.8-4.2.0.jar.jar"
      )
      val metalsProject =
        TestProject(
          workspace,
          projectName,
          Nil,
          scalaVersion = Some(testedScalaVersion),
          scalacOptions = defaultScalacOptions //${getClass().getResource("semanticdb_2.12.8-4.1.11.jar")}
        )
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion),
        reapplySettings = false
      )
      val bspState =
        loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) {
          state =>
            val scalacOptions = state.scalaOptions(metalsProject)._2.items.head.options
            val expected = defaultScalacOptions :+ "-Yrangepos"
            assert(scalacOptions == expected)
        }
    }
  }

  test("initialize metals client with existing plugin and -Yrangepos in workspace") {
    TestUtil.withinWorkspace { workspace =>
      val defaultScalacOptions = List(
        "-P:semanticdb:failures:warning",
        s"-P:semanticdb:sourceroot:$workspace",
        "-P:semanticdb:synthetics:on",
        "-Xplugin-require:semanticdb",
        s"-Xplugin:path-to-plugin/semanticdb-scalac_2.12.8-4.2.0.jar.jar",
        "-Yrangepos"
      )
      val metalsProject =
        TestProject(
          workspace,
          projectName,
          Nil,
          scalaVersion = Some(testedScalaVersion),
          scalacOptions = defaultScalacOptions //${getClass().getResource("semanticdb_2.12.8-4.1.11.jar")}
        )
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion),
        reapplySettings = false
      )
      val bspState =
        loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) {
          state =>
            val scalacOptions = state.scalaOptions(metalsProject)._2.items.head.options
            assert(scalacOptions == defaultScalacOptions)
        }
    }
  }

  test("force reload of all projects if reapplySettings is set to true") {
    TestUtil.withinWorkspace { workspace =>
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion),
        reapplySettings = true
      )
      val metalsProject = TestProject(workspace, projectName, Nil)
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      WorkspaceSettings.write(
        configDir,
        WorkspaceSettings(semanticdbVersion, List(testedScalaVersion))
      )
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams)(_ => ())
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assert(logger.infos.contains("Forcing reload of all projects"))
      }
    }
  }

  test("should save workspace settings with cached build") {
    TestUtil.withinWorkspace { workspace =>
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion),
        reapplySettings = false
      )
      val metalsProject = TestProject(workspace, projectName, Nil)
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      WorkspaceSettings.write(
        configDir,
        WorkspaceSettings(semanticdbVersion, List(testedScalaVersion))
      )
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, projects, logger, "Metals")(_ => ())
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
        val settings = WorkspaceSettings.fromFile(configDir, logger)
        assert(settings.isDefined && settings.get.semanticDBVersion == semanticdbVersion)
      }
    }
  }

  test("initialize multiple metals clients and save settings") {
    TestUtil.withinWorkspace { workspace =>
      val poolFor6Clients: Scheduler = Scheduler(
        java.util.concurrent.Executors.newFixedThreadPool(20),
        ExecutionModel.Default
      )
      val metalsProject =
        TestProject(workspace, projectName, Nil, scalaVersion = Some(testedScalaVersion))
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)

      def createClient(
          semanticdbVersion: String,
          clientName: String = "normalClient"
      ): Task[UnmanagedBspTestState] = {
        Task {
          val extraParams = BloopExtraBuildParams(
            clientClassesRootDir = None,
            semanticdbVersion = Some(semanticdbVersion),
            supportedScalaVersions = List(testedScalaVersion),
            reapplySettings = false
          )
          val bspLogger = new BspClientLogger(logger)
          val bspCommand = createBspCommand(configDir)
          val state = TestUtil.loadTestProject(configDir.underlying, logger)
          val scheduler = Some(poolFor6Clients)
          val bspState = openBspConnection(
            state,
            bspCommand,
            configDir,
            bspLogger,
            userIOScheduler = scheduler,
            clientName = clientName,
            bloopExtraParams = extraParams
          )

          assert(bspState.status == ExitStatus.Ok)
          // wait for all clients to connect
          Thread.sleep(500)
          bspState
        }
      }

      val normalClientsVersion = "4.2.0"
      val metalsClientVersion = "4.1.11"
      val client1 = createClient(normalClientsVersion)
      val client2 = createClient(normalClientsVersion)
      val client3 = createClient(normalClientsVersion)
      val client4 = createClient(normalClientsVersion)
      val client5 = createClient(normalClientsVersion)
      val metalsClient = createClient(metalsClientVersion, "Metals")

      val allClients = List(client1, client2, client3, client4, client5, metalsClient)
      TestUtil.await(FiniteDuration(5, "s"), poolFor6Clients) {
        Task.gatherUnordered(allClients).map(_ => ())
      }

      assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
      val settings = WorkspaceSettings.fromFile(configDir, logger)
      assert(settings.isDefined && settings.get.semanticDBVersion == metalsClientVersion)
    }
  }

  test("compile with semanticDB") {
    TestUtil.withinWorkspace { workspace =>
      val sources = List(
        """/main/scala/Foo.scala
          |class Foo
          """.stripMargin
      )

      val metalsProject = TestProject(workspace, projectName, sources)
      val projects = List(metalsProject)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      WorkspaceSettings.write(configDir, WorkspaceSettings("4.2.0", List(testedScalaVersion)))
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val bspState = loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(metalsProject).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        val classesDir = compiledState.client.getUniqueClassesDirFor(
          compiledState.build.getProjectFor(projectName).get
        )
        val semanticDBFile =
          classesDir.resolve(s"META-INF/semanticdb/$projectName/src/main/scala/Foo.scala.semanticdb")
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
      WorkspaceSettings.write(configDir, WorkspaceSettings("4.1.11", List(testedScalaVersion)))
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val bspState = loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(metalsProject).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        val classesDir = compiledState.client.getUniqueClassesDirFor(
          compiledState.build.getProjectFor(projectName).get
        )
        val semanticDBFile =
          classesDir.resolve(s"META-INF/semanticdb/$projectName/src/main/scala/Foo.scala.semanticdb")
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
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some("4.2.0"),
        supportedScalaVersions = List(testedScalaVersion),
        reapplySettings = false
      )
      val bspState =
        loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) {
          state =>
            val compiledState = state.compile(metalsProject).toTestState
            assert(compiledState.status == ExitStatus.Ok)
            val classesDir = compiledState.client.getUniqueClassesDirFor(
              compiledState.build.getProjectFor(projectName).get
            )
            val semanticDBFile =
              classesDir.resolve(s"META-INF/semanticdb/$projectName/src/main/scala/Foo.scala.semanticdb")
            assert(semanticDBFile.exists)
        }
    }
  }
}
