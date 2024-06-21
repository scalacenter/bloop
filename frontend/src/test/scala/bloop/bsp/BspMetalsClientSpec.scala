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
import bloop.internal.build.BuildTestInfo
import bloop.io.AbsolutePath
import bloop.io.Environment.LineSplitter
import bloop.io.Environment.lineSeparator
import bloop.logging.BspClientLogger
import bloop.logging.RecordingLogger
import bloop.task.Task
import bloop.util.TestProject
import bloop.util.TestUtil
import bloop.Compiler
import java.nio.file.Files

object LocalBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Local)
object TcpBspMetalsClientSpec extends BspMetalsClientSpec(BspProtocol.Tcp)

class BspMetalsClientSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  private val testedScalaVersion = BuildInfo.scalaVersion
  private val semanticdbVersion = BuildTestInfo.semanticdbVersion
  private val javaSemanticdbVersion = "0.10.0"

  private val semanticdbJar = s"semanticdb-scalac_$testedScalaVersion-$semanticdbVersion.jar"

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
        javaSemanticdbVersion = Some(javaSemanticdbVersion),
        enableBestEffortMode = None
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
          """|-J--add-exports
             |-Jjdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
             |-J--add-exports
             |-Jjdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
             |-J--add-exports
             |-Jjdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
             |-J--add-exports
             |-Jjdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
             |-J--add-exports
             |-Jjdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
             |-Xplugin:semanticdb -sourceroot:$workspace -targetroot:javac-classes-directory
             |""".stripMargin
        )
      }
    }
  }

  test("initialize metals client in workspace with already enabled semanticdb") {
    TestUtil.withinWorkspace { workspace =>
      val pluginPath = s"-Xplugin:path-to-plugin/$semanticdbJar"
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
        javaSemanticdbVersion = Some(javaSemanticdbVersion),
        enableBestEffortMode = None
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
        s"-Xplugin:path-to-plugin/$semanticdbJar",
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
        javaSemanticdbVersion = Some(javaSemanticdbVersion),
        enableBestEffortMode = None
      )

      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        assertNoDiffInSettingsFile(
          configDir,
          expectedConfig
        )

        val scalacOptions = state.scalaOptions(`A`)._2.items.head.options
        val expectedScalacOptions = correctSourceRootOption :: List(
          s"-Xplugin:path-to-plugin/semanticdb-scalac_${BuildInfo.scalaVersion}-${semanticdbVersion}.jar",
          "-Yrangepos",
          "-P:semanticdb:failures:warning",
          "-P:semanticdb:synthetics:on",
          "-Xplugin-require:semanticdb"
        )
        assert(scalacOptions == expectedScalacOptions)
      }
    }
  }

  test("should save workspace settings with cached build") {
    TestUtil.withinWorkspace { workspace =>
      val javaSemanticdbVersion = "0.10.0"
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion),
        enableBestEffortMode = None
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
            javaSemanticdbVersion = Some(javaSemanticdbVersion),
            enableBestEffortMode = None
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

      val javaNormalClientsVersion = "0.10.0"
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
          .fromSemanticdbSettings("0.10.0", semanticdbVersion, List(testedScalaVersion)),
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

  test("compile with old semanticDB") {
    TestUtil.withinWorkspace { workspace =>
      object JavacOptions {
        // This will cause to use the forked javac compiler, since addong any `-J` property causes it
        val A = List("-J-Xms48m")
      }

      val `A` =
        TestProject(
          workspace,
          "A",
          dummyFooScalaAndBarJavaSources,
          javacOptions = JavacOptions.A,
          // this Scala version is not supported in the newest semanticdb
          scalaVersion = Some("2.12.8")
        )
      val projects = List(`A`)
      val configDir = TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      WorkspaceSettings.writeToFile(
        configDir,
        WorkspaceSettings
          .fromSemanticdbSettings("0.10.0", semanticdbVersion, List(testedScalaVersion)),
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
          .fromSemanticdbSettings("0.10.0", semanticdbVersion, List(testedScalaVersion)),
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
        WorkspaceSettings.fromSemanticdbSettings("0.10.0", "4.3.0", List()),
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
        WorkspaceSettings.fromSemanticdbSettings("0.10.0", "4.3.0", List()),
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
        javaSemanticdbVersion = Some(javaSemanticdbVersion),
        enableBestEffortMode = None
      )
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSemanticdbFileFor("Bar.java", compiledState)
      }
    }
  }

  test("save-compile-semanticDB-many-options") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        dummyFooScalaAndBarJavaSources,
        scalacOptions = List("-release", "8", "-Ybackend-parallelism", "8")
      )
      val projects = List(`A`)
      TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion),
        None
      )
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        val compiledState = state.compile(`A`).toTestState
        assert(compiledState.status == ExitStatus.Ok)
        assertSemanticdbFileFor("Foo.scala", compiledState)
        assertSemanticdbFileFor("Bar.java", compiledState)
      }
    }
  }

  val bestEffortScalaVersion = "3.5.0-RC1"
  test("best-effort: compile dependency of failing project and produce semanticdb and betasty") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        dummyBestEffortSources,
        scalaVersion = Some(bestEffortScalaVersion)
      )
      val `B` = TestProject(
        workspace,
        "B",
        dummyBestEffortDepSources,
        directDependencies = List(`A`),
        scalaVersion = Some(bestEffortScalaVersion)
      )
      val projects = List(`A`, `B`)
      TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(bestEffortScalaVersion)),
        javaSemanticdbVersion = None,
        enableBestEffortMode = Some(true)
      )
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        val compiledStateA = state.compile(`A`, arguments = Some(List("--best-effort"))).toTestState
        assert(compiledStateA.status == ExitStatus.CompilationError)
        assertSemanticdbFileFor("TypeError.scala", compiledStateA, "A")
        assertBetastyFile("TypeError.betasty", compiledStateA, "A")
        val compiledStateB = state.compile(`B`, arguments = Some(List("--best-effort"))).toTestState
        assert(compiledStateB.status == ExitStatus.CompilationError)
        assertSemanticdbFileFor("TypeErrorDependency.scala", compiledStateB, "B")
        assertBetastyFile("TypeErrorDependency.betasty", compiledStateB, "B")

        val projectB = compiledStateB.build.getProjectFor("B").get
        compiledStateB.results.all(projectB) match {
          case Compiler.Result.Failed(problemsPerPhase, crash, _, _, _) =>
            assert(problemsPerPhase == List.empty) // No new errors should be found
            assert(crash == None)
          case result => fail(s"Result ${result} is not classified as failure")
        }
      }
    }
  }

  test("best-effort: regain artifacts after disconnecting and reconnecting to the client") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        dummyBestEffortSources,
        scalaVersion = Some(bestEffortScalaVersion)
      )
      val `B` = TestProject(
        workspace,
        "B",
        dummyBestEffortDepSources,
        directDependencies = List(`A`),
        scalaVersion = Some(bestEffortScalaVersion)
      )
      val projects = List(`A`, `B`)
      TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(bestEffortScalaVersion)),
        javaSemanticdbVersion = None,
        enableBestEffortMode = Some(true)
      )
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        val compiledStateA = state.compile(`A`, arguments = Some(List("--best-effort"))).toTestState
        val compiledStateB = state.compile(`B`, arguments = Some(List("--best-effort"))).toTestState
      }
      loadBspState(
        workspace,
        projects,
        logger,
        "Metals reconnected",
        bloopExtraParams = extraParams
      ) { state =>
        val compiledStateA = state.compile(`A`, arguments = Some(List("--best-effort"))).toTestState
        assertSemanticdbFileFor("TypeError.scala", compiledStateA, "A")
        assertBetastyFile("TypeError.betasty", compiledStateA, "A")
        val compiledStateB = state.compile(`B`, arguments = Some(List("--best-effort"))).toTestState
        assertSemanticdbFileFor("TypeErrorDependency.scala", compiledStateB, "B")
        assertBetastyFile("TypeErrorDependency.betasty", compiledStateB, "B")
        state.findBuildTarget(`A`)
      }
    }
  }

  test("best-effort: correctly manage betasty files when compiling correct and failing projects") {
    val initFile =
      """/ErrorFile.scala
        |object A
        |object B
        |""".stripMargin
    val updatedFile1WithError =
      """|object A
         |//object B
         |error
         |object C
         |""".stripMargin
    val updatedFile2WithoutError =
      """|//object A
         |object B
         |//error
         |object C
         |""".stripMargin
    val updatedFile3WithError =
      """|//object A
         |object B
         |error
         |//object C
         |""".stripMargin

    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(
        workspace,
        "A",
        List(initFile),
        scalaVersion = Some(bestEffortScalaVersion)
      )
      def updateProject(content: String) =
        Files.write(`A`.config.sources.head.resolve("ErrorFile.scala"), content.getBytes())
      val projects = List(`A`)
      TestProject.populateWorkspace(workspace, projects)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(bestEffortScalaVersion)),
        javaSemanticdbVersion = None,
        enableBestEffortMode = Some(true)
      )
      loadBspState(workspace, projects, logger, "Metals", bloopExtraParams = extraParams) { state =>
        val compiledState = state.compile(`A`, arguments = Some(List("--best-effort"))).toTestState
        assertBetastyFile("A.betasty", compiledState, "A")
        assertBetastyFile("B.betasty", compiledState, "A")
        assertCompilationFile("A.class", compiledState, "A")
        updateProject(updatedFile1WithError)
        val compiledState2 = state.compile(`A`, arguments = Some(List("--best-effort"))).toTestState
        assertBetastyFile("A.betasty", compiledState2, "A")
        assertNoBetastyFile("B.betasty", compiledState2, "A")
        assertBetastyFile("C.betasty", compiledState2, "A")
        assertNoCompilationFile("A.class", compiledState, "A")
        updateProject(updatedFile2WithoutError)
        val compiledState3 = state.compile(`A`, arguments = Some(List("--best-effort"))).toTestState
        assertNoBetastyFile("A.betasty", compiledState3, "A")
        assertBetastyFile("B.betasty", compiledState3, "A")
        assertBetastyFile("C.betasty", compiledState3, "A")
        assertCompilationFile("B.class", compiledState, "A")
        updateProject(updatedFile3WithError)
        val compiledState4 = state.compile(`A`, arguments = Some(List("--best-effort"))).toTestState
        assertNoBetastyFile("A.betasty", compiledState4, "A")
        assertBetastyFile("B.betasty", compiledState4, "A")
        assertNoBetastyFile("C.betasty", compiledState4, "A")
        assertNoCompilationFile("B.class", compiledState, "A")
      }
    }
  }

  test("compile is successful with semanticDB and javac processorpath") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)

      val projectName = "scala-java-processorpath"
      val localScalaVersion = BuildInfo.scalaVersion
      val localSemanticdbJar = s"semanticdb-scalac_$localScalaVersion-$semanticdbVersion.jar"

      val extraParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        clientClassesRootDir = None,
        semanticdbVersion = Some(semanticdbVersion),
        supportedScalaVersions = Some(List(testedScalaVersion)),
        javaSemanticdbVersion = Some(javaSemanticdbVersion),
        None
      )

      loadBspBuildFromResources(projectName, workspace, logger, "Metals", extraParams) { build =>
        val project = build.projectFor(projectName)
        val state = build.state

        assertNoDiff(logger.warnings.mkString(lineSeparator), "")
        assertNoDiffInSettingsFile(
          build.rawState.build.origin,
          expectedConfig
        )
        val projectPath = project.baseDir
        assertScalacOptions(
          state,
          project,
          s"""-Xplugin-require:semanticdb
             |-P:semanticdb:failures:warning
             |-P:semanticdb:sourceroot:$projectPath
             |-P:semanticdb:synthetics:on
             |-Xplugin:$localSemanticdbJar
             |-Yrangepos
             |""".stripMargin
        )
        val javacOptions = state.javacOptions(project)._2.items.flatMap(_.options)
        val javaSemanticDBJar = "semanticdb-javac-0.10.0.jar"
        assert(
          javacOptions(javacOptions.indexOf("-processorpath") + 1).contains(javaSemanticDBJar)
        )

        val compiledState = build.state.compile(project)
        assert(compiledState.status == ExitStatus.Ok)

        assertSemanticdbFileForProject(
          "/main/scala/example/Main.scala",
          compiledState.toTestState,
          projectName
        )
        assertSemanticdbFileForProject(
          "/main/java/example/FoobarValueAnalyzer.java",
          compiledState.toTestState,
          projectName
        )
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

  private val dummyBestEffortSources = List(
    """/TypeError.scala
      |object TypeError:
      |  val num: Int = ""
      |""".stripMargin
  )
  private val dummyBestEffortDepSources = List(
    """/TypeErrorDependency.scala
      |object TypeErrorDependency:
      |  def num(): Int = TypeError.num
      |""".stripMargin
  )

  private def assertSemanticdbFileForProject(
      sourceFileName: String,
      state: TestState,
      projectName: String
  ): Unit = {
    val file = semanticdbFileProject(sourceFileName, state, projectName)
    assertIsFile(file)
  }

  private def semanticdbFileProject(
      sourceFileName: String,
      state: TestState,
      projectName: String
  ) = {
    val project = state.build.getProjectFor(projectName).get
    val classesDir = state.client.getUniqueClassesDirFor(project, forceGeneration = true)
    classesDir.resolve(s"META-INF/semanticdb/src/$sourceFileName.semanticdb")
  }

  private def semanticdbFile(sourceFileName: String, state: TestState, projectName: String) = {
    val projectA = state.build.getProjectFor(projectName).get
    val classesDir = state.client.getUniqueClassesDirFor(projectA, forceGeneration = true)
    val sourcePath = if (sourceFileName.startsWith("/")) sourceFileName else s"/$sourceFileName"
    classesDir.resolve(s"META-INF/semanticdb/$projectName/src/$sourcePath.semanticdb")
  }

  private def assertCompilationFile(
      expectedFilePath: String,
      state: TestState,
      projectName: String
  ): Unit = {
    val project = state.build.getProjectFor(projectName).get
    val classesDir = state.client.getUniqueClassesDirFor(project, forceGeneration = true)
    assertIsFile(classesDir.resolve(expectedFilePath))
  }

  private def assertNoCompilationFile(
      expectedFilePath: String,
      state: TestState,
      projectName: String
  ): Unit = {
    val project = state.build.getProjectFor(projectName).get
    val classesDir = state.client.getUniqueClassesDirFor(project, forceGeneration = true)
    assertNotFile(classesDir.resolve(expectedFilePath))
  }

  private def assertBetastyFile(
      expectedBetastyRelativePath: String,
      state: TestState,
      projectName: String
  ): Unit = {
    assertCompilationFile(
      s"META-INF/best-effort/$expectedBetastyRelativePath",
      state,
      projectName
    )
  }

  private def assertNoBetastyFile(
      expectedBetastyRelativePath: String,
      state: TestState,
      projectName: String
  ): Unit = {
    assertNoCompilationFile(
      s"META-INF/best-effort/$expectedBetastyRelativePath",
      state,
      projectName
    )
  }

  private def assertSemanticdbFileFor(
      sourceFileName: String,
      state: TestState,
      projectName: String = "A"
  ): Unit = {
    val file = semanticdbFile(sourceFileName, state, projectName)
    assertIsFile(file)
  }

  private def assertNoSemanticdbFileFor(
      sourceFileName: String,
      state: TestState,
      projectName: String = "A"
  ): Unit = {
    val file = semanticdbFile(sourceFileName, state, projectName)
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
      .mkString(lineSeparator)
    assertNoDiff(
      javacOptions.mkString(lineSeparator),
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
