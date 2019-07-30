package bloop.bsp

import bloop.io.AbsolutePath
import bloop.cli.BspProtocol
import bloop.util.TestUtil
import bloop.util.TestProject
import bloop.logging.RecordingLogger
import bloop.logging.BspClientLogger
import bloop.cli.ExitStatus
import bloop.data.WorkspaceSettings
import bloop.internal.build.BuildInfo
import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams

import java.nio.file.Files

import io.circe.JsonObject
import io.circe.Json

import monix.execution.Scheduler
import monix.execution.ExecutionModel
import monix.eval.Task

import scala.concurrent.duration.FiniteDuration

import ch.epfl.scala.bsp.endpoints.BuildTarget.scalacOptions

object LocalBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Local)
object TcpBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Tcp)

class BspMetalsClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  private val testedScalaVersion = "2.12.8"

  test("initialize metals client and save settings") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "A", Nil, scalaVersion = Some(testedScalaVersion))
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion)
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiff(logger.warnings.mkString(System.lineSeparator), "")
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion" : "4.2.0",
             |    "supportedScalaVersions" : [
             |        "2.12.8"
             |    ]
             |}
             |""".stripMargin
        )
        assertScalacOptions(
          state,
          `A`,
          """|-Xplugin-require:semanticdb
             |-P:semanticdb:failures:warning
             |-P:semanticdb:sourceroot:$workspace
             |-P:semanticdb:synthetics:on
             |-Xplugin:semanticdb-scalac_2.12.8-4.2.0.jar
             |-Yrangepos
             |""".stripMargin
        )
      }
    }
  }

  test("do not initialize metals client and save settings with unsupported scala version") {
    TestUtil.withinWorkspace { workspace =>
      val semanticdbVersion = "4.2.0" // Doesn't support 2.12.4
      val `A` = TestProject(workspace, "A", Nil, scalaVersion = Some("2.12.4"))
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List("2.12.8")
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion" : "4.2.0",
             |    "supportedScalaVersions" : [
             |        "2.12.8"
             |    ]
             |}
             |""".stripMargin
        )
        // Expect only range positions to be added, semanticdb is not supported
        assertScalacOptions(state, `A`, "-Yrangepos")
        assertNoDiff(logger.warnings.mkString(System.lineSeparator), "")
      }
    }
  }

  test("initialize metals client in workspace with already enabled semanticdb") {
    TestUtil.withinWorkspace { workspace =>
      val defaultScalacOptions = List(
        "-P:semanticdb:failures:warning",
        s"-P:semanticdb:sourceroot:$workspace",
        "-P:semanticdb:synthetics:on",
        "-Xplugin-require:semanticdb",
        s"-Xplugin:path-to-plugin/semanticdb-scalac_2.12.8-4.2.0.jar.jar"
      )

      val `A` = TestProject(
        workspace,
        "A",
        Nil,
        scalaVersion = Some(testedScalaVersion),
        scalacOptions = defaultScalacOptions
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion)
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion" : "4.2.0",
             |    "supportedScalaVersions" : [
             |        "2.12.8"
             |    ]
             |}
             |""".stripMargin
        )

        val scalacOptions = state.scalaOptions(`A`)._2.items.head.options
        val expected = defaultScalacOptions :+ "-Yrangepos"
        assert(scalacOptions == expected)
      }
    }
  }

  test("initialize metals client in workspace with already enabled semanticdb and -Yrangepos") {
    TestUtil.withinWorkspace { workspace =>
      val defaultScalacOptions = List(
        "-P:semanticdb:failures:warning",
        s"-P:semanticdb:sourceroot:$workspace",
        "-P:semanticdb:synthetics:on",
        "-Xplugin-require:semanticdb",
        s"-Xplugin:path-to-plugin/semanticdb-scalac_2.12.8-4.2.0.jar.jar",
        "-Yrangepos"
      )
      val `A` = TestProject(
        workspace,
        "A",
        Nil,
        scalaVersion = Some(testedScalaVersion),
        scalacOptions = defaultScalacOptions
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion)
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion" : "4.2.0",
             |    "supportedScalaVersions" : [
             |        "2.12.8"
             |    ]
             |}
             |""".stripMargin
        )

        val scalacOptions = state.scalaOptions(`A`)._2.items.head.options
        assert(scalacOptions == defaultScalacOptions)
      }
    }
  }

  test("should save workspace settings with cached build") {
    TestUtil.withinWorkspace { workspace =>
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = List(testedScalaVersion)
      )
      val `A` = TestProject(workspace, "A", Nil)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings(semanticdbVersion, List(testedScalaVersion)),
        logger
      )

      def checkSettings: Unit = {
        assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
        val settings = WorkspaceSettings.readFromFile(configDir, logger)
        assert(settings.isDefined && settings.get.semanticDBVersion == semanticdbVersion)
      }

      loadBspState(workspace, projects, logger, "Metals")(_ => checkSettings)
      loadBspState(workspace, projects, logger, "unrecognized")(_ => checkSettings)
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { _ =>
        checkSettings
      }
    }
  }

  test("initialize multiple metals clients and save settings") {
    TestUtil.withinWorkspace { workspace =>
      val poolFor6Clients: Scheduler = Scheduler(
        java.util.concurrent.Executors.newFixedThreadPool(20),
        ExecutionModel.Default
      )
      val `A` = TestProject(workspace, "A", Nil, scalaVersion = Some(testedScalaVersion))
      val projects = List(`A`)
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
            supportedScalaVersions = List(testedScalaVersion)
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
      TestUtil.await(FiniteDuration(10, "s"), poolFor6Clients) {
        Task.gatherUnordered(allClients).map(_ => ())
      }

      assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
      val settings = WorkspaceSettings.readFromFile(configDir, logger)
      assert(settings.isDefined && settings.get.semanticDBVersion == metalsClientVersion)
    }
  }

  test("compile with semanticDB") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "A", dummyFooSources)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings("4.2.0", List(testedScalaVersion)),
        logger
      )
      val bspState = loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
      }
    }
  }

  test("compile with semanticDB using cached plugin") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "A", dummyFooSources)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings("4.1.11", List(testedScalaVersion)),
        logger
      )
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
      }
    }
  }

  test("save settings and compile with semanticDB") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "A", dummyFooSources)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        clientClassesRootDir = None,
        semanticdbVersion = Some("4.2.0"),
        supportedScalaVersions = List(testedScalaVersion)
      )
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
      }
    }
  }

  private val dummyFooSources = List(
    """/Foo.scala
      |class Foo
          """.stripMargin
  )

  private def assertSemanticdbFileFor(sourceFileName: String, state: TestState): Unit = {
    val projectA = state.build.getProjectFor("A").get
    val classesDir = state.client.getUniqueClassesDirFor(projectA)
    val sourcePath = if (sourceFileName.startsWith("/")) sourceFileName else s"/$sourceFileName"
    assertIsFile(
      classesDir.resolve(s"META-INF/semanticdb/A/src/$sourcePath.semanticdb")
    )
  }

  private def assertNoDiffInSettingsFile(configDir: AbsolutePath, expected: String): Unit = {
    val settingsFile = configDir.resolve(WorkspaceSettings.settingsFileName)
    assertIsFile(settingsFile)
    assertNoDiff(
      readFile(settingsFile),
      expected
    )
  }

  private def assertScalacOptions(
      state: ManagedBspTestState,
      project: TestProject,
      unorderedExpectedOptions: String
  ): Unit = {
    // Not the best way to obtain workspace but valid for tests
    val workspaceDir = state.underlying.build.origin.getParent.syntax
    val scalacOptions = state.scalaOptions(project)._2.items.flatMap(_.options).map { opt =>
      if (!opt.startsWith("-Xplugin:")) opt
      else {
        opt.split(":") match {
          case Array(key, value) => s"$key:${value.split(java.io.File.separator).last}"
        }
      }
    }

    val expectedOptions = unorderedExpectedOptions
      .replace("$workspace", workspaceDir)
      .split(System.lineSeparator)
      .filterNot(_.isEmpty)
      .sorted
      .mkString(System.lineSeparator)
    assertNoDiff(
      scalacOptions.sorted.mkString(System.lineSeparator),
      expectedOptions
    )
  }
}
