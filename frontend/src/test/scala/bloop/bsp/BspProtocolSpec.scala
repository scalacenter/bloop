package bloop.bsp

import java.net.URI

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

        val port = URI.create(address.uri).getPort
        assert(port == 48761)
      }
    }
  }

  test("check the correct contents of scalac options") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `A.scala` =
          """/A.scala
            |object A
          """.stripMargin
        val `B.scala` =
          """/B.scala
            |object B
          """.stripMargin
      }

      object ScalacOptions {
        val A = List("-Ywarn-unused-import")
        val B = List("-Yprint-typer")
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`A.scala`),
        scalacOptions = ScalacOptions.`A`
      )

      val `B` = TestProject(
        workspace,
        "b",
        List(Sources.`B.scala`),
        List(`A`),
        enableTests = true,
        scalacOptions = ScalacOptions.`B`
      )

      val projects = List(`A`, `B`)
      loadBspState(workspace, projects, logger) { state =>
        def testOptions(
            result: bsp.ScalacOptionsResult,
            expectedOptions: List[String],
            expectedClassesDir: Path,
            expectedId: bsp.BuildTargetIdentifier,
            expectedProjectEntries: List[Path]
        ): Unit = {
          assert(result.items.size == 1)
          val optionsItem = result.items.head
          assert(optionsItem.options == expectedOptions)
          assert(optionsItem.classDirectory.toPath == expectedClassesDir)
          assert(optionsItem.target == expectedId)
          val pathClasspath = optionsItem.classpath.map(_.toPath)
          expectedProjectEntries.foreach { expectedProjectEntry =>
            // Ensure there is only one match per every entry
            val matches = pathClasspath.filter(_ == expectedProjectEntry)
            assert(matches == List(expectedProjectEntry))
          }
        }

        val (stateA, resultA) = state.scalaOptions(`A`)
        assert(stateA.status == ExitStatus.Ok)
        val classesDirA = stateA.toTestState.getClientExternalDir(`A`).underlying
        testOptions(resultA, ScalacOptions.A, classesDirA, `A`.bspId, List(classesDirA))

        val (stateB, resultB) = state.scalaOptions(`B`)
        assert(stateB.status == ExitStatus.Ok)
        val classesDirB = stateB.toTestState.getClientExternalDir(`B`).underlying
        testOptions(resultB, ScalacOptions.B, classesDirB, `B`.bspId, List(classesDirB))
      }
    }
  }

  test("find main classes") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Main.scala` =
          """/main/scala/foo/Main.scala
            |package foo
            |object Main {
            | def main(args: Array[String]): Unit = ???
            |}
          """.stripMargin

        val `ClassWithMainFunc.scala` =
          """/main/scala/foo/NotMain.scala
            |package foo
            |class ClassWithMainFunc {
            | def main(args: Array[String]): Unit = ???
            |}
          """.stripMargin

        val `InheritedMain.scala` =
          """/main/scala/foo/InheritedMain.scala
            |package foo
            |object InheritedMain extends ClassWithMainFunc
          """.stripMargin

        val all = List(`Main.scala`, `ClassWithMainFunc.scala`, `InheritedMain.scala`)
      }
      val expectedClasses = Set("foo.Main", "foo.InheritedMain")

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val project = TestProject(workspace, "p", Sources.all)

      loadBspState(workspace, List(project), logger) { state =>
        val compilation = state.compile(project)
        assert(compilation.status == ExitStatus.Ok)

        val mainClasses = state.mainClasses(project)
        val items = mainClasses.items
        assert(items.size == 1)

        val classes = items.head.classes.map(_.`class`).toSet
        assert(classes == expectedClasses)
      }
    }
  }

  test("find test classes") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-0.6", workspace, logger) { build =>
        val project = build.projectFor("test-project-test")
        val expectedClasses = Set(
          "JUnitTest",
          "ScalaTestTest",
          "ScalaCheckTest",
          "WritingTest",
          "Specs2Test",
          "EternalUTest",
          "UTestTest",
          "ResourcesTest"
        ).map("hello." + _)

        val testClasses = build.state.testClasses(project)
        val items = testClasses.items
        assert(items.size == 1)

        val classes = items.head.classes.toSet
        assertEquals(classes, expectedClasses)
      }
    }
  }

  test("build targets request works on complicated build") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-0-6")
        val rootTest = build.projectFor("cross-test-build-0-6-test")

        def checkTarget(project: TestProject): Unit = {
          val bspTarget = build.state.findBuildTarget(project)
          assert(bspTarget.languageIds.sorted == List("java", "scala"))
          val json = bspTarget.data.get
          bsp.ScalaBuildTarget.decodeScalaBuildTarget(json.hcursor) match {
            case Right(scalaTarget) =>
              val expectedVersion = project.config.scala.get.version
              val expectedPlatform = project.config.platform.get match {
                case _: Config.Platform.Jvm => bsp.ScalaPlatform.Jvm
                case _: Config.Platform.Js => bsp.ScalaPlatform.Js
                case _: Config.Platform.Native => bsp.ScalaPlatform.Native
              }

              assert(scalaTarget.jars.nonEmpty)
              assert(scalaTarget.scalaOrganization.nonEmpty)
              assert(expectedVersion == scalaTarget.scalaVersion)
              assert(expectedVersion.startsWith(scalaTarget.scalaBinaryVersion))
              assert(scalaTarget.platform == expectedPlatform)
            case Left(e) => fail(s"Couldn't decode scala build target for ${bspTarget}")
          }
        }

        checkTarget(mainProject)
        checkTarget(testProject)
        checkTarget(mainJsProject)
        checkTarget(testJsProject)
        checkTarget(rootMain)
        checkTarget(rootTest)
      }
    }
  }

  test("build targets should be empty in build with recursive dependencies") {
    import bloop.io.RelativePath
    import bloop.logging.BspClientLogger
    val logger = new RecordingLogger(ansiCodesSupported = false)
    val bspLogger = new BspClientLogger(logger)
    val configDir = TestUtil.createSimpleRecursiveBuild(RelativePath("bloop-config"))
    val state = TestUtil.loadTestProject(configDir.underlying, logger)
    val bspCommand = createBspCommand(configDir)
    openBspConnection(state, bspCommand, configDir, bspLogger).withinSession { state =>
      val workspaceTargets = state.workspaceTargets
      assert(workspaceTargets.targets.isEmpty)
    }
  }

  test("sources request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-0-6")
        val rootTest = build.projectFor("cross-test-build-0-6-test")

        def checkSources(project: TestProject): Unit = {
          val sourcesResult = build.state.requestSources(project)
          assert(sourcesResult.items.size == 1)
          val sources = sourcesResult.items.head
          val sourcePaths = sources.sources.map(_.uri.toPath).toSet
          val expectedSources = project.config.sources.toSet
          assert(sourcePaths == expectedSources)
          val generateSources = sources.sources.filter(_.generated)
          assert(generateSources.isEmpty)
        }

        checkSources(mainProject)
        checkSources(testProject)
        checkSources(mainJsProject)
        checkSources(testJsProject)
        checkSources(rootMain)
        checkSources(rootTest)
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
