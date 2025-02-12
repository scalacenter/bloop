package bloop.bsp

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

import scala.util._

import ch.epfl.scala.bsp.JvmEnvironmentItem
import ch.epfl.scala.bsp.ScalacOptionsItem
import ch.epfl.scala.bsp.Uri

import bloop.bsp.BloopBspDefinitions.BloopExtraBuildParams
import bloop.cli.BspProtocol
import bloop.cli.ExitStatus
import bloop.config.Config
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.testing.DiffAssertions.TestFailedException
import bloop.util.TestProject
import bloop.util.TestUtil

import com.github.plokhotnyuk.jsoniter_scala.core._

object TcpBspProtocolSpec extends BspProtocolSpec(BspProtocol.Tcp)
object LocalBspProtocolSpec extends BspProtocolSpec(BspProtocol.Local)

class BspProtocolSpec(
    override val protocol: BspProtocol
) extends BspBaseSuite {
  import ch.epfl.scala.bsp
  test("check the correct contents of scalac/javac options") {
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

      object JavacOptions {
        val A = List("-source", "9")
      }

      val logger = new RecordingLogger(ansiCodesSupported = false)
      val `A` = TestProject(
        workspace,
        "a",
        List(Sources.`A.scala`),
        scalacOptions = ScalacOptions.`A`,
        javacOptions = JavacOptions.A
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
          assert(ProjectUris.toPath(optionsItem.classDirectory) == expectedClassesDir)
          assert(optionsItem.target == expectedId)
          val pathClasspath = optionsItem.classpath.map(ProjectUris.toPath)
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

        val (_, javacResultA) = state.javacOptions(`A`)
        assert(javacResultA.items.flatMap(_.options) == JavacOptions.A)
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
          |object A {
          |  def main(args: Array[String]): Unit = ???
          |}
          """.stripMargin
    }

    val logger = new RecordingLogger(ansiCodesSupported = false)
    val workingDirectory = AbsolutePath.workingDirectory.underlying.resolve("cwd")
    val workingDirectoryOption = List(s"-Duser.dir=$workingDirectory")
    val jvmOptions = List("-DSOME_OPTION=X") ++ workingDirectoryOption
    val jvmConfig = Some(Config.JvmConfig(None, jvmOptions))
    val runtimeJvmOptions = List("-DOTHER_OPTION=Y") ++ workingDirectoryOption
    val runtimeJvmConfig = Some(Config.JvmConfig(None, runtimeJvmOptions))
    val runtimeMainClasses = Some(List(bsp.JvmMainClass("A", Nil)))
    val `A` = TestProject(
      workspace,
      "a",
      List(Sources.`A.scala`),
      jvmConfig = jvmConfig,
      runtimeJvmConfig = runtimeJvmConfig
    )

    val projects = List(`A`)

    loadBspState(workspace, projects, logger) { state =>
      val compileState = state.compile(`A`)
      val (stateA: ManagedBspTestState, environmentItems: List[JvmEnvironmentItem]) =
        extractor(compileState, `A`)
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

      assert(environmentItem.jvmOptions == runtimeJvmOptions)
      assert(environmentItem.mainClasses == runtimeMainClasses)
    }
  }

  test("check-jvm-test-environment") {
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

  test("check-jvm-main-environment") {
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
        supportedScalaVersions = None,
        javaSemanticdbVersion = None,
        enableBestEffortMode = None
      )

      // Start first client and query for scalac options which creates client classes dirs
      loadBspState(workspace, projects, logger, bloopExtraParams = extraBloopParams) { bspState =>
        val (_, options) = bspState.scalaOptions(`A`)
        firstScalacOptions = options.items
        firstScalacOptions.foreach(d =>
          assertIsDirectory(AbsolutePath(ProjectUris.toPath(d.classDirectory)))
        )
      }

      // Start second client and query for scalac options which should use same dirs as before
      loadBspState(workspace, projects, logger, bloopExtraParams = extraBloopParams) { bspState =>
        val (_, options) = bspState.scalaOptions(`A`)
        secondScalacOptions = options.items
        secondScalacOptions.foreach(d =>
          assertIsDirectory(AbsolutePath(ProjectUris.toPath(d.classDirectory)))
        )
      }

      firstScalacOptions.zip(secondScalacOptions).foreach {
        case (firstItem, secondItem) =>
          assertNoDiff(
            firstItem.classDirectory,
            secondItem.classDirectory
          )
      }

      firstScalacOptions.foreach { option =>
        assertIsDirectory(AbsolutePath(ProjectUris.toPath(option.classDirectory)))
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

        val classes = items.head.classes.map(_.className).toSet
        assert(classes == expectedClasses)
      }
    }
  }

  test("find test classes") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val project = build.projectFor("test-project-test")
        val compiledState = build.state.compile(project, timeout = 120)
        val expectedSuites = Set(
          ("JUnit", List("hello.JUnitTest")),
          ("ScalaCheck", List("hello.ScalaCheckTest")),
          ("specs2", List("hello.Specs2Test")),
          ("utest", List("hello.EternalUTest", "hello.UTestTest")),
          ("ScalaTest", List("hello.ScalaTestTest", "hello.WritingTest", "hello.ResourcesTest"))
        ).map {
          case (framework, classes) =>
            bsp.ScalaTestClassesItem(project.bspId, Some(framework), classes)
        }

        val testSuites = compiledState.testClasses(project)
        val items = testSuites.items

        assert(items.size == expectedSuites.size)

        try assertEquals(items.toSet, expectedSuites)
        catch { case t: TestFailedException => logger.dump(); throw t }
      }
    }
  }

  test("find test classes") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("simple-build", workspace, logger) { build =>
        val project = build.projectFor("a")
        val compiledState = build.state.compile(project, timeout = 120)

        compiledState.state.build.loadedProjects.foreach { project =>
          assert(compiledState.status == ExitStatus.Ok)
          project.project.resolution.toIterable.flatMap(_.modules).flatMap(_.artifacts).foreach {
            artifact =>
              if (artifact.path.toString.contains("sourcecode")) {
                artifact.path.toFile().delete()
              }
          }
        }
      }
      loadBspBuildFromResources("simple-build", workspace, logger) { build =>
        val project = build.projectFor("a")
        val compiledState = build.state.compile(project, timeout = 120)
        assert(compiledState.status == ExitStatus.Ok)
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
          Try(readFromArray[bsp.ScalaBuildTarget](json.value)) match {
            case Success(scalaTarget) =>
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
            case Failure(_) => fail(s"Couldn't decode scala build target for ${bspTarget}")
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
    def bspCommand() = createBspCommand(configDir)
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
          val expectedResources = project.config.resources.getOrElse(Seq.empty).toSet
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

  test("outputPaths request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-scalajs-0-6")
        val rootTest = build.projectFor("cross-test-build-scalajs-0-6-test")

        def checkOutputPaths(project: TestProject): Unit = {
          val outputPathsResult = build.state.requestOutputPaths(project)
          assert(outputPathsResult.items.size == 1)
          val outputPathsItem = outputPathsResult.items.head
          assert(outputPathsItem.target == project.bspId)
          val outputPaths = outputPathsItem.outputPaths.map(_.uri.toPath)
          val expectedOutputPaths = List(project.config.out.toAbsolutePath())
          assert(outputPaths == expectedOutputPaths)
        }

        checkOutputPaths(mainProject)
        checkOutputPaths(testProject)
        checkOutputPaths(mainJsProject)
        checkOutputPaths(testJsProject)
        checkOutputPaths(rootMain)
        checkOutputPaths(rootTest)
      }
    }
  }

  test("dependency modules request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-scalajs-0-6")
        val rootTest = build.projectFor("cross-test-build-scalajs-0-6-test")

        def checkDependencyModules(project: TestProject): Unit = {
          val dependencyModulesResult = build.state.requestDependencyModules(project)
          assert(dependencyModulesResult.items.size == 1)
          val dependencyModules = dependencyModulesResult.items.flatMap(item =>
            item.modules
              .map(dependencyModule => {
                val json = dependencyModule.data.get
                val mavenModule = readFromArray[bsp.MavenDependencyModule](json.value)
                val artifacts: List[Config.Artifact] = mavenModule.artifacts
                  .map(artifact =>
                    Config.Artifact(
                      dependencyModule.name,
                      artifact.classifier,
                      None,
                      artifact.uri.toPath
                    )
                  )
                  .distinct;
                new Config.Module(
                  mavenModule.organization,
                  mavenModule.name,
                  mavenModule.version,
                  None,
                  artifacts
                )
              })
              .distinct
          )

          val expectedModules = project.config.resolution.toList.flatMap { res =>
            res.modules.map { m =>
              val artifacts = m.artifacts.map(artifact => artifact.copy(checksum = None))
              m.copy(configurations = None, artifacts = artifacts)
            }
          }.distinct

          assertEquals(dependencyModules, expectedModules)
        }

        checkDependencyModules(mainProject)
        checkDependencyModules(testProject)
        checkDependencyModules(mainJsProject)
        checkDependencyModules(testJsProject)
        checkDependencyModules(rootMain)
        checkDependencyModules(rootTest)
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

  test("inverse sources request works") {
    TestUtil.withinWorkspace { workspace =>
      val logger = new RecordingLogger(ansiCodesSupported = false)
      loadBspBuildFromResources("cross-test-build-scalajs-0.6", workspace, logger) { build =>
        val mainProject = build.projectFor("test-project")
        val testProject = build.projectFor("test-project-test")
        val mainJsProject = build.projectFor("test-projectJS")
        val testJsProject = build.projectFor("test-projectJS-test")
        val rootMain = build.projectFor("cross-test-build-scalajs-0-6")
        val rootTest = build.projectFor("cross-test-build-scalajs-0-6-test")

        def checkInverseSources(project: TestProject): Unit = {
          project.sources.foreach { source =>
            val inverseSourcesResult = build.state.requestInverseSources(source)
            assert(inverseSourcesResult.targets.contains(project.bspId))
          }
        }

        checkInverseSources(mainProject)
        checkInverseSources(testProject)
        checkInverseSources(mainJsProject)
        checkInverseSources(testJsProject)
        checkInverseSources(rootMain)
        checkInverseSources(rootTest)
      }
    }
  }

}
