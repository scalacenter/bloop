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

import java.nio.file.{Files, Paths}

import io.circe.JsonObject
import io.circe.Json

import monix.execution.Scheduler
import monix.execution.ExecutionModel
import monix.eval.Task

import scala.concurrent.duration.FiniteDuration

import ch.epfl.scala.bsp.endpoints.BuildTarget.scalacOptions
import bloop.engine.ExecutionContext
import scala.util.Random

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
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion))
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiff(logger.warnings.mkString(System.lineSeparator), "")
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion": "4.2.0",
             |    "supportedScalaVersions": [
             |        "2.12.8"
             |    ],
             |    "traceProperties": {
             |        "zipkinServerUrl": "http://127.0.0.1:9411/api/v2/spans",
             |        "debug": false,
             |        "verbose": false,
             |        "localServiceName": "bloop"
             |    }
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
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List("2.12.8"))
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion": "4.2.0",
             |    "supportedScalaVersions": [
             |        "2.12.8"
             |    ],
             |    "traceProperties": {
             |        "zipkinServerUrl": "http://127.0.0.1:9411/api/v2/spans",
             |        "debug": false,
             |        "verbose": false,
             |        "localServiceName": "bloop"
             |    }
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
      val pluginPath = s"-Xplugin:path-to-plugin/semanticdb-scalac_2.12.8-4.2.0.jar.jar"
      val defaultScalacOptions = List(
        "-P:semanticdb:failures:warning",
        s"-P:semanticdb:sourceroot:$workspace",
        "-P:semanticdb:synthetics:on",
        "-Xplugin-require:semanticdb",
        pluginPath
      )

      val `A` = TestProject(
        workspace,
        "A",
        Nil,
        scalaVersion = Some(testedScalaVersion),
        scalacOptions = List(pluginPath)
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion))
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion": "4.2.0",
             |    "supportedScalaVersions": [
             |        "2.12.8"
             |    ],
             |    "traceProperties": {
             |        "zipkinServerUrl": "http://127.0.0.1:9411/api/v2/spans",
             |        "debug": false,
             |        "verbose": false,
             |        "localServiceName": "bloop"
             |    }
             |}
             |""".stripMargin
        )

        val scalacOptions = state.scalaOptions(`A`)._2.items.head.options.toSet
        val expected = (defaultScalacOptions :+ "-Yrangepos").toSet
        assert(scalacOptions == expected)
      }
    }
  }

  test(
    "initialize metals client in workspace with already enabled semanticdb, -Yrangepos and invalid sourcepath"
  ) {
    TestUtil.withinWorkspace { workspace =>
      val correctSourceRootOption = s"-P:semanticdb:sourceroot:$workspace"
      val defaultScalacOptions = List(
        s"-P:semanticdb:sourceroot:bad-root",
        "-P:semanticdb:failures:warning",
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
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion))
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          """|{
             |    "semanticDBVersion": "4.2.0",
             |    "supportedScalaVersions": [
             |        "2.12.8"
             |    ],
             |    "traceProperties": {
             |        "zipkinServerUrl": "http://127.0.0.1:9411/api/v2/spans",
             |        "debug": false,
             |        "verbose": false,
             |        "localServiceName": "bloop"
             |    }
             |}
             |""".stripMargin
        )

        val scalacOptions = state.scalaOptions(`A`)._2.items.head.options
        assert(scalacOptions == correctSourceRootOption :: defaultScalacOptions.drop(1))
      }
    }
  }

  test("should save workspace settings with cached build") {
    TestUtil.withinWorkspace { workspace =>
      val semanticdbVersion = "4.2.0"
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion))
      )
      val `A` = TestProject(workspace, "A", Nil)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings.fromSemanticdbSettings(semanticdbVersion, List(testedScalaVersion)),
        logger
      )

      def checkSettings: Unit = {
        assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
        val settings = WorkspaceSettings.readFromFile(configDir, logger)
        assert(settings.isDefined && settings.get.semanticDBVersion.get == semanticdbVersion)
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
            ownsBuildFiles = None,
            clientClassesRootDir = None,
            semanticdbVersion = Some(semanticdbVersion),
            supportedScalaVersions = Some(List(testedScalaVersion))
          )
          val bspLogger = new BspClientLogger(logger)
          val bspCommand = createBspCommand(configDir)
          val state = TestUtil.loadTestProject(configDir.underlying, logger)
          val scheduler = Some(ExecutionContext.ioScheduler)
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
      val metalsClient = createClient(metalsClientVersion, "Metals")

      val allClients = Random.shuffle(List(client1, client2, metalsClient))
      TestUtil.await(FiniteDuration(20, "s"), ExecutionContext.ioScheduler) {
        Task.gatherUnordered(allClients).map(_ => ())
      }

      assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
      val settings = WorkspaceSettings.readFromFile(configDir, logger)
      assert(settings.isDefined && settings.get.semanticDBVersion.get == metalsClientVersion)
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
        WorkspaceSettings.fromSemanticdbSettings("4.2.0", List(testedScalaVersion)),
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
        WorkspaceSettings.fromSemanticdbSettings("4.1.11", List(testedScalaVersion)),
        logger
      )
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
      }
    }
  }

  test("compile producing Semanticdb with scala3 without using plugin") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        dummyFooSources,
        scalaVersion = Some("0.23.0-RC1"),
        scalaOrg = Some("ch.epfl.lamp"),
        scalaCompiler = Some("dotty-compiler_0.23")
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings.fromSemanticdbSettings("4.3.0", List()),
        logger
      )
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSuccessfulCompilation(compiledState, projects, isNoOp = false)
      }
    }
  }

  test("compile not producing Semanticdb with scala3 when settings are not present") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        dummyFooSources,
        scalaVersion = Some("0.23.0-RC1"),
        scalaOrg = Some("ch.epfl.lamp"),
        scalaCompiler = Some("dotty-compiler_0.23")
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertSuccessfulCompilation(compiledState, projects, isNoOp = false)
        assertNoSemanticdbFileFor("Foo.scala", compiledState)
      }
    }
  }

  test("compile producing Semanticdb with scala3 when -Ysemanticdb setting is already present") {
    TestUtil.withinWorkspace { workspace =>
      val defaultScalacOptions = List(
        "-Ysemanticdb"
      )
      val `A` = TestProject(
        workspace,
        "A",
        dummyFooSources,
        scalaVersion = Some("0.23.0-RC1"),
        scalacOptions = defaultScalacOptions,
        scalaOrg = Some("ch.epfl.lamp"),
        scalaCompiler = Some("dotty-compiler_0.23")
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings.fromSemanticdbSettings("4.3.0", List()),
        logger
      )
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSuccessfulCompilation(compiledState, projects, isNoOp = false)
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
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some("4.2.0"),
        supportedScalaVersions = Some(List(testedScalaVersion))
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

  private def semanticdbFile(sourceFileName: String, state: TestState) = {
    val projectA = state.build.getProjectFor("A").get
    val classesDir = state.client.getUniqueClassesDirFor(projectA, forceGeneration = true)
    val sourcePath = if (sourceFileName.startsWith("/")) sourceFileName else s"/$sourceFileName"
    classesDir.resolve(s"META-INF/semanticdb/A/src/$sourcePath.semanticdb")
  }

  private def assertSemanticdbFileFor(
      sourceFileName: String,
      state: TestState
  ): Unit = {
    val file = semanticdbFile(sourceFileName, state)
    assertIsFile(file)
  }

  private def assertNoSemanticdbFileFor(
      sourceFileName: String,
      state: TestState
  ): Unit = {
    val file = semanticdbFile(sourceFileName, state)
    assertNotFile(file)
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
    val workspaceDir = project.config.workspaceDir
      .map(AbsolutePath(_))
      .getOrElse(state.underlying.build.origin.getParent)
      .syntax
    val scalacOptions = state.scalaOptions(project)._2.items.flatMap(_.options).map { opt =>
      if (!opt.startsWith("-Xplugin:")) opt
      else {
        val idx = opt.indexOf(":")
        opt.splitAt(idx) match {
          case (key, value) =>
            val pluginPath = Paths.get(value.tail).getFileName().toString
            s"$key:$pluginPath"
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
