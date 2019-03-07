package bloop.bsp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

import bloop.cli.{BspProtocol, CliOptions, Commands, CommonOptions, Validate}
import bloop.data.Project
import bloop.engine.{BuildLoader, Run}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.logging.{BspClientLogger, RecordingLogger}
import bloop.util.{TestProject, TestUtil}
import org.junit.Test
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.{BuildTargetIdentifier, ScalaBuildTarget, endpoints}
import junit.framework.Assert
import monix.eval.Task

import scala.meta.jsonrpc.{LanguageClient, Response, Services}
import scala.util.Try

class BspProtocolSpec {
  private final val configDir = AbsolutePath(TestUtil.getBloopConfigDir("cross-test-build-0.6"))
  private final val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  private final val MainProjectName = "test-project"
  private final val TestProjectName = "test-project-test"

  private final val MainJsProjectName = "test-projectJS"
  private final val TestJsProjectName = "test-projectJS-test"

  // Load the current build associated with the configuration directory to test project metadata
  private final val crossTestBuild = BuildLoader.loadSynchronously(configDir, new RecordingLogger)
  private val mainProject = crossTestBuild
    .find(_.name == MainProjectName)
    .getOrElse(sys.error(s"Missing main project $MainProjectName in $crossTestBuild"))
  Files.createDirectories(mainProject.baseDirectory.underlying)
  private val testProject = crossTestBuild
    .find(_.name == TestProjectName)
    .getOrElse(sys.error(s"Missing main project $TestProjectName in $crossTestBuild"))

  Files.createDirectories(testProject.baseDirectory.underlying)
  private val testTargetId = bsp.BuildTargetIdentifier(testProject.bspUri)
  private val mainTargetId = bsp.BuildTargetIdentifier(mainProject.bspUri)

  def isMainProject(targetUri: BuildTargetIdentifier): Boolean =
    targetUri.uri == mainProject.bspUri
  def isTestProject(targetUri: BuildTargetIdentifier): Boolean =
    targetUri.uri == testProject.bspUri

  def testInitialization(cmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    // We test the initialization several times to make sure the scheduler doesn't get blocked.
    def test(counter: Int): Unit = {
      if (counter == 0) ()
      else {
        BspClientTest.runTest(cmd, configDir, logger)(c => monix.eval.Task.eval(Right(())))
        test(counter - 1)
      }
    }

    BspClientTest.reportIfError(logger) {
      test(10)
      val CompleteHandshake = "BSP initialization handshake complete."
      val BuildInitialize = "\"method\" : \"build/initialize\""
      val BuildInitialized = "\"method\" : \"build/initialized\""
      val msgs = logger.underlying.getMessages.map(_._2)
      Assert.assertEquals(10, msgs.count(_.contains(BuildInitialize)))
      Assert.assertEquals(10, msgs.count(_.contains(BuildInitialized)))
      Assert.assertEquals(10, msgs.count(_.contains(CompleteHandshake)))
    }
  }

  def testBuildTargets(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).map {
        case Right(workspaceTargets) =>
          workspaceTargets.targets.foreach { t =>
            Assert.assertEquals(t.languageIds.sorted, List("java", "scala"))
            t.data.foreach { json =>
              ScalaBuildTarget.decodeScalaBuildTarget(json.hcursor) match {
                case Right(target) =>
                  // Test that the scala version is the correct one
                  Assert.assertTrue(
                    s"Scala bin version ${target.scalaBinaryVersion} == Scala version ${target.scalaVersion}",
                    target.scalaBinaryVersion != target.scalaVersion
                  )

                  val platform = target.platform
                  val expectedPlatform = t.displayName match {
                    case Some(MainProjectName) => bsp.ScalaPlatform.Jvm
                    case Some(TestProjectName) => bsp.ScalaPlatform.Jvm
                    case Some(MainJsProjectName) => bsp.ScalaPlatform.Js
                    case Some(TestJsProjectName) => bsp.ScalaPlatform.Js
                    // For the rest of the projects, assume JVM
                    case Some(_) => bsp.ScalaPlatform.Jvm
                    // This should never happen, bloop should always pass in the display name
                    case None => Assert.fail(s"Missing `displayName` in ${target}")
                  }

                  Assert.assertEquals(
                    s"Expected $expectedPlatform, obtained $platform platform for ${t.displayName}",
                    expectedPlatform,
                    platform
                  )

                case Left(failure) =>
                  sys.error(s"Decoding `${json}` as a scala build target failed: ${failure}")
              }
            }
          }
          Right(Assert.assertEquals(6, workspaceTargets.targets.size))
        case Left(error) => Left(error)
      }
    }

    BspClientTest.reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      val BuildInitialize = "\"method\" : \"build/initialize\""
      val BuildInitialized = "\"method\" : \"build/initialized\""
      val BuildTargets = "\"method\" : \"workspace/buildTargets\""
      val msgs = logger.underlying.getMessages.map(_._2)
      Assert.assertEquals(1, msgs.count(_.contains(BuildInitialize)))
      Assert.assertEquals(1, msgs.count(_.contains(BuildInitialized)))
      Assert.assertEquals(1, msgs.count(_.contains(BuildTargets)))
    }
  }

  def testBuildTargetsEmpty(bspCmd: Commands.ValidatedBsp, configDir: AbsolutePath): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).map {
        case Right(workspaceTargets) =>
          if (workspaceTargets.targets.isEmpty) Right(())
          else Left(Response.internalError(s"Workspace targets are not empty ${workspaceTargets}"))
        case Left(error) => Left(error)
      }
    }

    BspClientTest.reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      val recursiveError = "Fatal recursive dependency detected in 'g': List(g, g)"
      val errors = logger.underlying.getMessagesAt(Some("error"))
      Assert.assertEquals(1, errors.count(_.contains(recursiveError)))
    }
  }

  def testSources(bspCmd: Commands.ValidatedBsp): Unit = {
    def testSourcePerTarget(bti: BuildTargetIdentifier, project: Project)(
        implicit client: LanguageClient
    ) = {
      endpoints.BuildTarget.sources.request(bsp.SourcesParams(List(bti))).map {
        case Left(error) => Left(error)
        case Right(sources) =>
          val fetchedSources = sources.items.flatMap(i => i.sources.map(_.uri.value)).toSet
          Assert.assertFalse(
            s"Found jar in ${fetchedSources}",
            fetchedSources.exists(_.endsWith(".jar"))
          )

          val expectedSources = project.sources.map(s => bsp.Uri(s.toBspSourceUri).value).toSet
          val diff1 = fetchedSources.diff(expectedSources)
          val diff2 = expectedSources.diff(fetchedSources)
          Assert.assertTrue(s"Expecting fetched sources '$fetchedSources'", diff1.isEmpty)
          Assert.assertTrue(s"Expecting sources '$expectedSources'", diff2.isEmpty)
          Right(())
      }
    }
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          workspaceTargets.targets.find(_.displayName == Some(MainProjectName)) match {
            case Some(mainTarget) =>
              testSourcePerTarget(mainTarget.id, mainProject).flatMap {
                case Left(e) => Task.now(Left(e))
                case Right(_) =>
                  workspaceTargets.targets.find(_.displayName == Some(TestProjectName)) match {
                    case Some(testTarget) => testSourcePerTarget(testTarget.id, testProject)
                    case None =>
                      Task.now(
                        Left(Response.internalError(s"Missing test project in ${workspaceTargets}"))
                      )
                  }
              }
            case None =>
              Task.now(Left(Response.internalError(s"Missing main project in ${workspaceTargets}")))
          }
      }
    }

    BspClientTest.reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testDependencySources(bspCmd: Commands.ValidatedBsp): Unit = {
    def testSourcePerTarget(bti: BuildTargetIdentifier, project: Project)(
        implicit client: LanguageClient
    ) = {
      endpoints.BuildTarget.dependencySources.request(bsp.DependencySourcesParams(List(bti))).map {
        case Left(error) => Left(error)
        case Right(sources) =>
          val fetchedSources = sources.items.flatMap(i => i.sources.map(_.value)).toSet
          Assert.assertTrue(
            s"Found non-jar file in ${fetchedSources}",
            fetchedSources.forall(_.endsWith(".jar"))
          )

          val expectedSources = project.resolution.toList.flatMap { res =>
            res.modules.flatMap { m =>
              m.artifacts.iterator
                .filter(a => a.classifier.toList.contains("sources"))
                .map(a => bsp.Uri(AbsolutePath(a.path).toBspUri).value)
                .toList
            }
          }.toSet

          val diff1 = fetchedSources.diff(expectedSources)
          val diff2 = expectedSources.diff(fetchedSources)
          Assert.assertTrue(s"Expecting fetched sources '$fetchedSources'", diff1.isEmpty)
          Assert.assertTrue(s"Expecting sources '$expectedSources'", diff2.isEmpty)
          Right(())
      }
    }

    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          val btis = workspaceTargets.targets.map(_.id)
          workspaceTargets.targets.find(_.displayName == Some(MainProjectName)) match {
            case Some(mainTarget) =>
              testSourcePerTarget(mainTarget.id, mainProject).flatMap {
                case Left(e) => Task.now(Left(e))
                case Right(_) =>
                  workspaceTargets.targets.find(_.displayName == Some(TestProjectName)) match {
                    case Some(testTarget) => testSourcePerTarget(testTarget.id, testProject)
                    case None =>
                      Task.now(
                        Left(Response.internalError(s"Missing test project in ${workspaceTargets}"))
                      )
                  }
              }
            case None =>
              Task.now(Left(Response.internalError(s"Missing main project in ${workspaceTargets}")))
          }
      }
    }

    BspClientTest.reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testScalacOptions(bspCmd: Commands.ValidatedBsp): Unit = {
    def stringify(xs: Seq[String]) = xs.sorted.mkString(";")
    def stringifyOptions(
        scalacOptions0: Seq[String],
        classpath0: Seq[bsp.Uri],
        classesDir: bsp.Uri
    ): String = {
      val scalacOptions = stringify(scalacOptions0)
      val classpath = stringify(classpath0.map(_.value))
      s"""StringifiedScalacOption($scalacOptions, $classpath, ${classesDir.value})"""
    }

    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          val btis = workspaceTargets.targets.map(_.id)
          endpoints.BuildTarget.scalacOptions.request(bsp.ScalacOptionsParams(btis)).map {
            case Left(error) => Left(error)
            case Right(options) =>
              val uriOptions = options.items.map(i => (i.target.uri.value, i)).sortBy(_._1)
              val expectedUriOptions = BuildLoader
                .loadSynchronously(configDir, logger.underlying)
                .map(p => (p.bspUri.value, p))
                .sortBy(_._1)

              Assert
                .assertEquals("Size of options differ", uriOptions.size, expectedUriOptions.size)
              uriOptions.zip(expectedUriOptions).foreach {
                case ((obtainedUri, opts), (expectedUri, p)) =>
                  Assert.assertEquals(obtainedUri, expectedUri)
                  val obtainedOptions =
                    stringifyOptions(opts.options, opts.classpath, opts.classDirectory)
                  val state = TestUtil.loadTestProject(configDir.underlying, identity(_))
                  val classpath = p
                    .fullClasspath(state.build.getDagFor(p), state.client)
                    .map(i => bsp.Uri(i.toBspUri))
                    .toList
                  val classesDir = bsp.Uri(p.genericClassesDir.toBspUri)
                  val expectedOptions =
                    stringifyOptions(p.scalacOptions.toList, classpath, classesDir)
                  Assert.assertEquals(obtainedOptions, expectedOptions)
              }

              Right(uriOptions)
          }
      }
    }

    BspClientTest.reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testTest(bspCmd: Commands.ValidatedBsp): Unit = {
    var compiledMainProject: Boolean = false
    var compiledTestProject: Boolean = false
    var checkTestedTargets: Boolean = false
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.map(_.id).find(isTestProject(_)) match {
              case Some(id) =>
                endpoints.BuildTarget.test.request(bsp.TestParams(List(id), None, None, None)).map {
                  case Left(e) => Left(e)
                  case Right(report) =>
                    val valid = compiledMainProject && compiledTestProject && checkTestedTargets
                    if (valid) Right(report)
                    else Left(Response.internalError("Didn't receive all compile or test reports."))
                }
              case None => Task.now(Left(Response.internalError(s"Missing '$TestProjectName'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed testing with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      /*      s.notification(endpoints.BuildTarget.compileReport) { report =>
          if (compiledMainProject && compiledTestProject)
            Assert.fail(s"Bloop compiled unexpected target: ${report}")
          val targetUri = report.target
          if (isMainProject(targetUri)) {
            compiledMainProject = true
            Assert.assertEquals(s"Warnings in $MainProject != 1", 1, report.warnings)
            Assert.assertEquals(s"Errors in $MainProject != 0", 0, report.errors)
          } else if (isTestProject(targetUri)) {
            compiledTestProject = true
            Assert.assertEquals(s"Warnings in $TestProject != 0", 0, report.warnings)
            Assert.assertEquals(s"Errors in $TestProject != 0", 0, report.errors)
          } else ()
        }
        .notification(endpoints.BuildTarget.testReport) { report =>
          if (checkTestedTargets)
            Assert.fail(s"Bloop unexpected only one test report, received: ${report}")
          if (isTestProject(report.target)) {
            checkTestedTargets = true
            Assert.assertEquals("Successful tests != 115", 115, report.passed)
            Assert.assertEquals(s"Failed tests ${report.failed}", 0, report.failed)
          }
        }*/
      s
    }

    BspClientTest.reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      /*      Assert.assertTrue(
        "Test execution did not compile the main and test projects.",
        msgs.filter(_.contains("Done compiling.")).size == 2
      )*/
    }
  }

  def testRun(bspCmd: Commands.ValidatedBsp): Unit = {
    var compiledMainProject: Boolean = false
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.map(_.id).find(isMainProject(_)) match {
              case Some(id) =>
                endpoints.BuildTarget.run.request(bsp.RunParams(id, None, None)).map {
                  case Left(e) => Left(e)
                  case Right(result) =>
                    if (compiledMainProject) {
                      result.statusCode match {
                        case bsp.StatusCode.Ok => Right(result)
                        case bsp.StatusCode.Error =>
                          Left(Response.internalError("Status code of run is an error!"))
                        case bsp.StatusCode.Cancelled =>
                          Left(Response.internalError("Status code of cancelled is an error!"))
                      }
                    } else {
                      Left(Response.internalError("The test didn't receive any compile report."))
                    }
                }
              case None => Task.now(Left(Response.internalError(s"Missing '$MainProjectName'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed testing with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      /*      s.notification(endpoints.BuildTarget.compileReport) { report =>
        if (compiledMainProject)
          Assert.fail(s"Bloop compiled unexpected target: ${report}")

        val targetUri = report.target
        if (isMainProject(targetUri)) {
          compiledMainProject = true
          Assert.assertEquals(s"Warnings in $MainProject != 1", 1, report.warnings)
          Assert.assertEquals(s"Errors in $MainProject != 0", 0, report.errors)
        }
      }*/
      s
    }

    BspClientTest.reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      /*      Assert.assertTrue(
        s"Run execution did not compile $MainProject.",
        msgs.filter(_.contains("Done compiling.")).size == 1
      )*/
    }
  }

  // Check the BSP server errors correctly on unknown and empty targets in a compile request
  def testFailedCompileOnInvalidInputs(bspCmd: Commands.ValidatedBsp): Unit = {
    import BspClientTest.BspClientAction._

    val f = new java.net.URI("file://thisdoesntexist")
    val actions1 = List(Compile(bsp.BuildTargetIdentifier(bsp.Uri(f))))
    Try(BspClientTest.runCompileTest(bspCmd, actions1, configDir, true)) match {
      case scala.util.Success(_) =>
        Assert.fail("Expected error when compiling invalid target!")
      case scala.util.Failure(t) =>
        TestUtil.assertNoDiff(
          "Received error Error(ErrorObject(InternalError,Missing target BuildTargetIdentifier(Uri(file://thisdoesntexist)),None),Null)!",
          t.getMessage()
        )
    }

    Try(BspClientTest.runCompileTest(bspCmd, List(CompileEmpty), configDir, true)) match {
      case scala.util.Success(_) =>
        Assert.fail("Expected error when compiling no targets!")
      case scala.util.Failure(t) =>
        TestUtil.assertNoDiff(
          """Received error Error(ErrorObject(InternalError,Error when compiling no target: CompileResult(None,Error,None)
            |Empty build targets. Expected at least one build target identifier.,None),Null)!""".stripMargin,
          t.getMessage
        )
    }
  }

  import BspClientTest.{createLocalBspCommand, createTcpBspCommand}
  @Test def TestInitializationViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testInitialization(createLocalBspCommand(configDir, tempDir))
  }

  @Test def TestInitializationViaTcp(): Unit = {
    testInitialization(createTcpBspCommand(configDir))
  }

  @Test def TestBuildWithRecursiveDependenciesViaTcp(): Unit = {
    val configDir = TestUtil.createSimpleRecursiveBuild(RelativePath("bloop-config"))
    testBuildTargetsEmpty(createTcpBspCommand(configDir), configDir)
  }

  @Test def TestBuildTargetsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testBuildTargets(createLocalBspCommand(configDir, tempDir))
  }

  @Test def TestBuildTargetsViaTcp(): Unit = {
    testBuildTargets(createTcpBspCommand(configDir))
  }

  @Test def TestSourcesViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testSources(createLocalBspCommand(configDir, tempDir))
  }

  @Test def TestSourcesViaTcp(): Unit = {
    testSources(createTcpBspCommand(configDir))
  }

  @Test def TestDependencySourcesViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testDependencySources(createLocalBspCommand(configDir, tempDir))
  }

  @Test def TestDependencySourcesViaTcp(): Unit = {
    testDependencySources(createTcpBspCommand(configDir))
  }

  @Test def TestScalacOptionsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testScalacOptions(createLocalBspCommand(configDir, tempDir))
  }

  @Test def TestScalacOptionsViaTcp(): Unit = {
    testScalacOptions(createTcpBspCommand(configDir))
  }

  // TODO(jvican): Enable these tests back after partial migration to v2 is done
  /*  @Test def TestTestViaLocal(): Unit = {
    if (!BspServer.isWindows) testTest(createLocalBspCommand(configDir))
  }

  @Test def TestTestViaTcp(): Unit = {
    testTest(createTcpBspCommand(configDir, verbose = true))
  }

  @Test def TestRunViaLocal(): Unit = {
    if (!BspServer.isWindows) testRun(createLocalBspCommand(configDir))
  }

  @Test def TestRunViaTcp(): Unit = {
    testRun(createTcpBspCommand(configDir, verbose = true))
  }*/

  @Test def TestFailedCompileViaLocal(): Unit = {
    if (!BspServer.isWindows)
      testFailedCompileOnInvalidInputs(createLocalBspCommand(configDir, tempDir))
  }

  @Test def TestFailedCompileViaTcp(): Unit = {
    testFailedCompileOnInvalidInputs(createTcpBspCommand(configDir))
  }
}
