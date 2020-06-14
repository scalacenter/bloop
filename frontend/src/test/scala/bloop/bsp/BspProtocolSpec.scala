package bloop.bsp

import java.net.URI
import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors

import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.cli.{BspProtocol, ExitStatus}
import bloop.config.Config
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, RecordingLogger}
import bloop.testing.DiffAssertions.TestFailedException
import bloop.util.{TestProject, TestUtil}
import ch.epfl.scala.bsp.{BuildTarget, JvmEnvironmentItem, ScalacOptionsItem, Uri}

import scala.collection.JavaConverters._

object TcpBspProtocolSpec extends BspProtocolSpec(BspProtocol.Tcp)
object LocalBspProtocolSpec extends BspProtocolSpec(BspProtocol.Local)

class BspProtocolSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  import ch.epfl.scala.bsp
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

  private def testEnvironmentFetching(
      workspace: AbsolutePath,
      extractor: (ManagedBspTestState, TestProject) => (
          ManagedBspTestState,
          List[JvmEnvironmentItem]
      )
  ) = {
    object Sources {
      val `A.scala` =
        """/A.scala
          |object A
          """.stripMargin
    }

    val logger = new RecordingLogger(ansiCodesSupported = false)
    val workingDirectory = AbsolutePath.workingDirectory.underlying.resolve("cwd")
    val jvmOptions = List("-DSOME_OPTION=X", s"-Duser.dir=$workingDirectory")
    val jvmConfig = Some(Config.JvmConfig(None, jvmOptions))
    val `A` = TestProject(
      workspace,
      "a",
      List(Sources.`A.scala`),
      jvmConfig = jvmConfig
    )

    val projects = List(`A`)

    loadBspState(workspace, projects, logger) { state =>
      val (stateA: ManagedBspTestState, environmentItems: List[JvmEnvironmentItem]) =
        extractor(state, `A`)
      assert(environmentItems.size == 1)
      assert(stateA.status == ExitStatus.Ok)

      val environmentItem = environmentItems.head
      assert(environmentItem.environmentVariables.contains("BLOOP_OWNER"))
      assertNoDiff(
        "BLOOP_OWNER",
        environmentItem.environmentVariables.keys.mkString("\n")
      )
      assert(
        Paths.get(environmentItem.workingDirectory).getFileName ==
          workingDirectory.getFileName()
      )
      assert(
        environmentItem.classpath
          .exists(_.contains(s"target/${`A`.config.name}"))
      )
      assert(
        environmentItem.classpath.forall(new URI(_).getScheme == "file")
      )

      assert(environmentItem.jvmOptions == jvmOptions)
    }
  }

  test("check the correct contents of jvm test environment") {
    TestUtil.withinWorkspace { workspace =>
      testEnvironmentFetching(
        workspace,
        (state: ManagedBspTestState, project: TestProject) => {
          val (stateA, result) = state.jvmTestEnvironment(project, None)
          val environmentItems = result.items
          (stateA, environmentItems)
        }
      )
    }
  }

  test("check the correct contents of jvm run environment") {
    TestUtil.withinWorkspace { workspace =>
      testEnvironmentFetching(
        workspace,
        (state: ManagedBspTestState, project: TestProject) => {
          val (stateA, result) = state.jvmRunEnvironment(project, None)
          val environmentItems = result.items
          (stateA, environmentItems)
        }
      )
    }
  }

  test("use client root classes directory and make sure project directories are stable") {
    TestUtil.withinWorkspace { workspace =>
      val `A` = TestProject(workspace, "a", Nil)
      val projects = List(`A`)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val userClientClassesRootDir = workspace.resolve("root-client-dirs")

      var firstScalacOptions: List[ScalacOptionsItem] = Nil
      var secondScalacOptions: List[ScalacOptionsItem] = Nil

      val extraBloopParams = BloopExtraBuildParams(
        ownsBuildFiles = None,
        Some(Uri(userClientClassesRootDir.toBspUri)),
        semanticdbVersion = None,
        supportedScalaVersions = None
      )

      // Start first client and query for scalac options which creates client classes dirs
      loadBspState(workspace, projects, logger, bloopExtraParams = extraBloopParams) { bspState =>
        val (_, options) = bspState.scalaOptions(`A`)
        firstScalacOptions = options.items
        firstScalacOptions.foreach(d => assertIsDirectory(AbsolutePath(d.classDirectory.toPath)))
      }

      // Start second client and query for scalac options which should use same dirs as before
      loadBspState(workspace, projects, logger, bloopExtraParams = extraBloopParams) { bspState =>
        val (_, options) = bspState.scalaOptions(`A`)
        secondScalacOptions = options.items
        secondScalacOptions.foreach(d => assertIsDirectory(AbsolutePath(d.classDirectory.toPath)))
      }

      firstScalacOptions.zip(secondScalacOptions).foreach {
        case (firstItem, secondItem) =>
          assertNoDiff(
            firstItem.classDirectory.value,
            secondItem.classDirectory.value
          )
      }

      firstScalacOptions.foreach { option =>
        assertIsDirectory(AbsolutePath(option.classDirectory.toPath))
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
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val project = build.projectFor("test-project-test")
        val compiledState = build.state.compile(project)
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

        val testClasses = compiledState.testClasses(project)
        val items = testClasses.items
        assert(items.size == 1)

        val classes = items.head.classes.toSet
        try assertEquals(classes, expectedClasses)
        catch { case t: TestFailedException => logger.dump(); throw t }
      }
    }
  }

  test("build targets request works on complicated build") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-scalajs-0-6")
        val rootTest = build.projectFor("cross-test-build-scalajs-0-6-test")

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
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-scalajs-0-6")
        val rootTest = build.projectFor("cross-test-build-scalajs-0-6-test")

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

  test("sources globs are expanded in sources request") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      object Sources {
        val `Hello.scala` =
          """/Hello.scala
            |object A
          """.stripMargin
        val `HelloTest.scala` =
          """/HelloTest.scala
            |object HelloTest
          """.stripMargin

        // This file is ignored because `walkDepth == 1`, meaning that the globs
        // only expand to file that are depth 1 from the base directory.
        val `toodeep/Hello.scala` =
          """/toodeep/HelloTest.scala
            |package toodeep
            |object Hello
          """.stripMargin
      }

      val globDirectory = workspace.resolve("a").resolve("src")
      val baseProject = TestProject(
        workspace,
        "a",
        sources = List(
          Sources.`Hello.scala`,
          Sources.`HelloTest.scala`,
          Sources.`toodeep/Hello.scala`
        ),
        sourcesGlobs = List(
          Config.SourcesGlobs(
            globDirectory.underlying,
            walkDepth = Some(1),
            includes = List("glob:*.scala"),
            excludes = List("glob:*Test.scala")
          )
        )
      )
      val `A` = baseProject.copy(config = baseProject.config.copy(sources = Nil))

      val projects = List(`A`)
      loadBspState(workspace, projects, logger) { state =>
        val project = state.underlying.main.build.loadedProjects.head
        def assertSourcesMatches(expected: String): Unit = {
          val obtained = for {
            item <- state.requestSources(`A`).items
            source <- item.sources
            path = AbsolutePath(source.uri.toPath)
          } yield path.toRelative(globDirectory).toUri(isDirectory = false).toString()
          assertNoDiff(
            obtained.sorted.mkString("\n"),
            expected
          )
        }
        assertSourcesMatches("Hello.scala")
        val hello2 = globDirectory.resolve("Hello2.scala")
        Files.write(hello2.underlying, Array.emptyByteArray)
        assertSourcesMatches(
          """Hello.scala
            |Hello2.scala
            |""".stripMargin
        )
        Files.delete(hello2.underlying)
        assertSourcesMatches("Hello.scala")
      }
    }
  }

  test("resources request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-scalajs-0-6")
        val rootTest = build.projectFor("cross-test-build-scalajs-0-6-test")

        def checkResources(project: TestProject): Unit = {
          val resourcesResult = build.state.requestResources(project)
          assert(resourcesResult.items.size == 1)
          val resources = resourcesResult.items.head
          val resourcePaths = resources.resources.map(_.toPath).toSet
          val expectedResources = project.config.resources
            .getOrElse(Seq.empty)
            .flatMap(
              dir =>
                if (Files.exists(dir)) Files.walk(dir).collect(Collectors.toList[Path]).asScala
                else Seq.empty
            )
            .toSet
          assert(resourcePaths == expectedResources)
        }

        checkResources(mainProject)
        checkResources(testProject)
        checkResources(mainJsProject)
        checkResources(testJsProject)
        checkResources(rootMain)
        checkResources(rootTest)
      }
    }
  }

  test("dependency sources request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-scalajs-0-6")
        val rootTest = build.projectFor("cross-test-build-scalajs-0-6-test")

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

  test("sbt meta projects - workspace targets") {
    TestUtil.withinWorkspace { workspace =>
      import bloop.util.TestProject
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val a = TestProject(workspace, "a", Nil)
      val rootDir = TestProject.populateWorkspace(workspace, List(a))

      val sbtProjectWorkspace = workspace.resolve("project")
      val sbtBuildProject = TestProject(sbtProjectWorkspace, "build", Nil)
      TestProject.populateWorkspace(sbtProjectWorkspace, List(sbtBuildProject))

      val bspLogger = new BspClientLogger(logger)
      val bspCommand = createBspCommand(rootDir)

      val state = loadState(rootDir, List.empty, logger, None).state
      openBspConnection(state, bspCommand, rootDir, bspLogger).withinSession { bspState =>
        val workspaceTargets = bspState.workspaceTargets.targets
        assertEquals(workspaceTargets.size, 2)

        def basePathForProject(name: String, all: List[BuildTarget]): AbsolutePath = {
          val t = all.find(_.displayName.contains(name)).get
          AbsolutePath(t.baseDirectory.get.toPath)
        }

        assertEquals(
          basePathForProject("a", workspaceTargets),
          workspace.resolve("a")
        )
        assertEquals(
          basePathForProject("build", workspaceTargets),
          sbtProjectWorkspace.resolve("build")
        )
      }
    }
  }

  test("sbt meta projects - compile") {
    TestUtil.withinWorkspace { workspace =>
      object Sources {
        val `Main.scala` =
          """/a/scala/foo/Main.scala
            |package foo
            |object Main {
            | def main(args: Array[String]): Unit = ???
            |}
          """.stripMargin

        val `Deps1.scala` =
          """/project/Deps.scala
            |object Deps {
            |  def foo(a: String): Int = a
            |}
          """.stripMargin

        val `Deps2.scala` =
          """/project/Deps.scala
            |object Deps {
            |  def foo(a: String): String = a
            |}
          """.stripMargin
      }

      import bloop.util.TestProject
      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(workspace, "a", List(Sources.`Main.scala`))
      val rootDir = TestProject.populateWorkspace(workspace, List(`A`))

      val sbtProjectWorkspace = workspace.resolve("project")
      val `Build` = TestProject(sbtProjectWorkspace, "build", List(Sources.`Deps1.scala`))
      TestProject.populateWorkspace(sbtProjectWorkspace, List(`Build`))

      val bspLogger = new BspClientLogger(logger)
      val bspCommand = createBspCommand(rootDir)

      val state = loadState(rootDir, List.empty, logger, None).state
      openBspConnection(state, bspCommand, rootDir, bspLogger).withinSession { bspState =>
        val workspaceTargets = bspState.workspaceTargets.targets
        assertEquals(workspaceTargets.size, 2)

        def idByName(name: String, all: List[BuildTarget]): bsp.BuildTargetIdentifier = {
          all.find(_.displayName.contains(name)).get.id
        }

        val aId = idByName("a", workspaceTargets)
        val buildId = idByName("build", workspaceTargets)

        val firstCompilation = bspState.compileRaw(List(aId, buildId))

        assertNoDiff(
          firstCompilation.lastDiagnostics(`Build`),
          """#1: task start 2
            |  -> Msg: Compiling build (1 Scala source)
            |  -> Data kind: compile-task
            |#1: project/build/src/project/Deps.scala
            |  -> List(Diagnostic(Range(Position(1,28),Position(1,28)),Some(Error),Some(_),Some(_),type mismatch;  found   : String  required: Int,None))
            |  -> reset = true
            |#1: task finish 2
            |  -> errors 1, warnings 0
            |  -> Msg: Compiled 'build'
            |  -> Data kind: compile-report
            |""".stripMargin
        )

        writeFile(`Build`.srcFor("/project/Deps.scala"), Sources.`Deps2.scala`)

        val secondCompilation = firstCompilation.compileRaw(List(aId, buildId))
        assertNoDiff(
          secondCompilation.lastDiagnostics(`Build`),
          """#2: task start 4
            |  -> Msg: Compiling build (1 Scala source)
            |  -> Data kind: compile-task
            |#2: project/build/src/project/Deps.scala
            |  -> List()
            |  -> reset = true
            |#2: task finish 4
            |  -> errors 0, warnings 0
            |  -> Msg: Compiled 'build'
            |  -> Data kind: compile-report
            |""".stripMargin
        )
      }
    }
  }
}
