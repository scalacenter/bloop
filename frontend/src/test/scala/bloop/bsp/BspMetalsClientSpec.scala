package bloop.bsp

import java.nio.file.Paths

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

import ch.epfl.scala.bsp.endpoints.BuildTarget.scalacOptions

import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.data.WorkspaceSettings
import bloop.engine.ExecutionContext
import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.io.Environment.LineSplitter
import bloop.io.Environment.lineSeparator
import bloop.logging.BspClientLogger
import bloop.logging.RecordingLogger
import bloop.task.Task
import bloop.util.TestProject
import bloop.util.TestUtil

object LocalBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Local)
object TcpBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Tcp)

class BspMetalsClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  private val testedScalaVersion = BuildInfo.scalaVersion
  require(
    testedScalaVersion == "2.12.17",
    "Updating scala version requires updating semanticDB plugin"
  )
  private val semanticdbVersion = "4.6.0"
  private val javaSemanticdbVersion = "0.5.7"
  private val semanticdbJar = "semanticdb-scalac_2.12.17-4.6.0.jar"

  private val expectedConfig =
    s"""|{
        |    "javaSemanticDBVersion": "$javaSemanticdbVersion",
        |    "semanticDBVersion": "$semanticdbVersion",
        |    "supportedScalaVersions": [
        |        "$testedScalaVersion"
        |    ]
        |}
        |""".stripMargin

  test("initialize metals client and save settings") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "A", Nil, scalaVersion = Some(testedScalaVersion))
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)

      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion)
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiff(logger.warnings.mkString(lineSeparator), "")
        assertNoDiffInSettingsFile(
          configDir,
          expectedConfig
        )
        assertScalacOptions(
          state,
          `A`,
          s"""-Xplugin-require:semanticdb
             |-P:semanticdb:failures:warning
             |-P:semanticdb:sourceroot:$workspace
             |-P:semanticdb:synthetics:on
             |-Xplugin:$semanticdbJar
             |-Yrangepos
             |""".stripMargin
        )
        assertJavacOptions(
          state,
          `A`,
          """|-Xplugin:semanticdb -sourceroot:$workspace -targetroot:javac-classes-directory
             |""".stripMargin
        )
      }
    }
  }

  test("do not initialize metals client and save settings with unsupported scala version") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "A", Nil, scalaVersion = Some("2.12.4"))
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion), // Doesn't support 2.12.4
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion)
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          expectedConfig
        )
        // Expect only range positions to be added, semanticdb is not supported
        assertScalacOptions(state, `A`, "-Yrangepos")
        assertNoDiff(logger.warnings.mkString(lineSeparator), "")
      }
    }
  }

  test("initialize metals client in workspace with already enabled semanticdb") {
    TestUtil.withinWorkspace { workspace =>
      val pluginPath = s"-Xplugin:path-to-plugin/semanticdb-scalac_2.12.17-4.6.0.jar"
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
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion)
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          expectedConfig
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
        s"-Xplugin:path-to-plugin/semanticdb-scalac_2.12.17-4.6.0.jar",
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
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion)
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          expectedConfig
        )

        val scalacOptions = state.scalaOptions(`A`)._2.items.head.options
        assert(scalacOptions == correctSourceRootOption :: defaultScalacOptions.drop(1))
      }
    }
  }

  test("should save workspace settings with cached build") {
    TestUtil.withinWorkspace { workspace =>
      val javaSemanticdbVersion = "0.5.7"
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion)
      )
      val `A` = TestProject(workspace, "A", Nil)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings.fromSemanticdbSettings(
          javaSemanticdbVersion,
          semanticdbVersion,
          List(testedScalaVersion)
        ),
        logger
      )

      def checkSettings: Unit = {
        assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
        val settings = WorkspaceSettings.readFromFile(configDir, logger)
        assert(
          settings.isDefined && settings.get.semanticDBVersion.isDefined && settings.get.semanticDBVersion.get == semanticdbVersion
        )
        assert(
          settings.isDefined && settings.get.javaSemanticDBVersion.isDefined && settings.get.javaSemanticDBVersion.get == javaSemanticdbVersion
        )
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
          javaSemanticdbVersion: String,
          semanticdbVersion: String,
          clientName: String = "normalClient"
      ): Task[UnmanagedBspTestState] = {
        Task {
          val extraParams = BloopExtraBuildParams(
            ownsBuildFiles = None,
            clientClassesRootDir = None,
            semanticdbVersion = Some(semanticdbVersion),
            supportedScalaVersions = Some(List(testedScalaVersion)),
            javaSemanticdbVersion = Some(javaSemanticdbVersion)
          )
          val bspLogger = new BspClientLogger(logger)
          def bspCommand() = createBspCommand(configDir)
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

      val javaNormalClientsVersion = "0.5.7"
      val javaMetalsVersion = "0.1.0"
      val normalClientsVersion = "4.2.0"
      val metalsClientVersion = "4.1.11"
      val client1 = createClient(javaNormalClientsVersion, normalClientsVersion)
      val client2 = createClient(javaNormalClientsVersion, normalClientsVersion)
      val metalsClient = createClient(javaMetalsVersion, metalsClientVersion, "Metals")

      val allClients = Random.shuffle(List(client1, client2, metalsClient))
      TestUtil.await(FiniteDuration(20, "s"), ExecutionContext.ioScheduler) {
        Task.gatherUnordered(allClients).map(_ => ())
      }

      assert(configDir.resolve(WorkspaceSettings.settingsFileName).exists)
      val settings = WorkspaceSettings.readFromFile(configDir, logger)
      assert(
        settings.isDefined && settings.get.semanticDBVersion.isDefined && settings.get.semanticDBVersion.get == metalsClientVersion
      )
      assert(
        settings.isDefined && settings.get.javaSemanticDBVersion.isDefined && settings.get.javaSemanticDBVersion.get == javaMetalsVersion
      )
    }
  }

  test("compile with semanticDB") {
    TestUtil.withinWorkspace { workspace =>
      object JavacOptions {
        // This will cause to use the forked javac compiler, since addong any `-J` property causes it
        val A = List("-J-Xms48m")
      }

      val `A` =
        TestProject(workspace, "A", dummyFooScalaAndBarJavaSources, javacOptions = JavacOptions.A)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings
          .fromSemanticdbSettings("0.5.7", semanticdbVersion, List(testedScalaVersion)),
        logger
      )
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSemanticdbFileFor("Bar.java", compiledState)
      }
    }
  }

  test("compile with semanticDB using cached plugin") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "A", dummyFooScalaAndBarJavaSources)
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings
          .fromSemanticdbSettings("0.5.7", semanticdbVersion, List(testedScalaVersion)),
        logger
      )
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSemanticdbFileFor("Bar.java", compiledState)
      }
    }
  }

  test("compile producing Semanticdb with scala3 without using plugin") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        dummyFooScalaAndBarJavaSources,
        scalaVersion = Some("3.0.0-M3"),
        scalaOrg = Some("org.scala-lang"),
        scalaCompiler = Some("scala3-compiler_3.0.0-M3")
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings.fromSemanticdbSettings("0.5.7", "4.3.0", List()),
        logger
      )
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSemanticdbFileFor("Bar.java", compiledState)
        assertSuccessfulCompilation(compiledState, projects, isNoOp = false)
      }
    }
  }

  test("compile not producing Semanticdb with scala3 when settings are not present") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        dummyFooScalaAndBarJavaSources,
        scalaVersion = Some("3.0.0-M3"),
        scalaOrg = Some("org.scala-lang"),
        scalaCompiler = Some("scala3-compiler_3.0.0-M3")
      )
      val projects = List(`A`)
      TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspState(workspace, projects, logger) { state =>
        val compiledState = state.compile(`A`).toTestState
        assertExitStatus(compiledState, ExitStatus.Ok)
        assertValidCompilationState(compiledState, projects)
        assertSuccessfulCompilation(compiledState, projects, isNoOp = false)
        assertNoSemanticdbFileFor("Foo.scala", compiledState)
        assertNoSemanticdbFileFor("Bar.java", compiledState)
      }
    }
  }

  test("compile producing Semanticdb with scala3 when -Xsemanticdb setting is already present") {
    TestUtil.withinWorkspace { workspace =>
      val defaultScalacOptions = List(
        "-Xsemanticdb"
      )
      val `A` = TestProject(
        workspace,
        "A",
        dummyFooScalaSources,
        scalaVersion = Some("3.0.0-M3"),
        scalacOptions = defaultScalacOptions,
        scalaOrg = Some("org.scala-lang"),
        scalaCompiler = Some("scala3-compiler_3.0.0-M3")
      )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings.fromSemanticdbSettings("0.5.7", "4.3.0", List()),
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
      val `A` = TestProject(workspace, "A", dummyFooScalaAndBarJavaSources)
      val projects = List(`A`)
      TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion)
      )
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSemanticdbFileFor("Bar.java", compiledState)
      }
    }
  }

  private val dummyFooScalaSources = List(
    """/Foo.scala
      |class Foo
          """.stripMargin
  )

  private val dummyBarJavaSources = List(
    """/Bar.java
      |class Bar {}
          """.stripMargin
  )

  private val dummyFooScalaAndBarJavaSources = dummyFooScalaSources ++ dummyBarJavaSources

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

  private def assertJavacOptions(
      state: ManagedBspTestState,
      project: TestProject,
      unorderedExpectedOptions: String
  ): Unit = {
    // Not the best way to obtain workspace but valid for tests
    val workspaceDir = project.config.workspaceDir
      .map(AbsolutePath(_))
      .getOrElse(state.underlying.build.origin.getParent)
      .syntax
    // plugin is on classpath so no need for full path to jar like scala plugin
    val javacOptions = state.javacOptions(project)._2.items.flatMap(_.options)

    val expectedOptions = unorderedExpectedOptions
      .replace("$workspace", workspaceDir)
      .splitLines
      .filterNot(_.isEmpty)
      .sorted
      .mkString(lineSeparator)
    assertNoDiff(
      javacOptions.sorted.mkString(lineSeparator),
      expectedOptions
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
      .splitLines
      .filterNot(_.isEmpty)
      .sorted
      .mkString(lineSeparator)
    assertNoDiff(
      scalacOptions.sorted.mkString(lineSeparator),
      expectedOptions
    )
  }
}
