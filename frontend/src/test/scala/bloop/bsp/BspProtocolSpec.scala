package bloop.bsp

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.cli.validation.Validate
import bloop.cli.{BspProtocol, CliOptions, Commands}
import bloop.engine.{BuildLoader, Run}
import bloop.io.{AbsolutePath, Paths}
import bloop.tasks.TestUtil
import bloop.logging.{BspClientLogger, DebugFilter, RecordingLogger}
import org.junit.Test
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.{BuildTargetIdentifier, ScalaBuildTarget, endpoints}
import junit.framework.Assert
import monix.eval.Task

import scala.meta.jsonrpc.{LanguageClient, Response, Services}
import scala.util.control.NonFatal

class BspProtocolSpec {
  private final val configDir = AbsolutePath(TestUtil.getBloopConfigDir("cross-test-build-0.6"))
  private final val cwd = AbsolutePath(configDir.underlying.getParent)
  private final val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  private final val MainProject = "test-project"
  def isMainProject(targetUri: BuildTargetIdentifier): Boolean =
    targetUri.uri.value.endsWith(MainProject)

  private final val TestProject = "test-project-test"
  def isTestProject(targetUri: BuildTargetIdentifier): Boolean =
    targetUri.uri.value.endsWith(TestProject)

  def validateBsp(bspCommand: Commands.Bsp): Commands.ValidatedBsp = {
    Validate.bsp(bspCommand, BspServer.isWindows) match {
      case Run(bsp: Commands.ValidatedBsp, _) => BspClientTest.setupBspCommand(bsp, cwd, configDir)
      case failed => sys.error(s"Command validation failed: ${failed}")
    }
  }

  def createLocalBspCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    val uniqueId = java.util.UUID.randomUUID().toString.take(4)
    val socketFile = tempDir.resolve(s"test-$uniqueId.socket")
    validateBsp(
      Commands.Bsp(
        protocol = BspProtocol.Local,
        socket = Some(socketFile),
        pipeName = Some(s"\\\\.\\pipe\\test-$uniqueId")
      )
    )
  }

  def createTcpBspCommand(
      configDir: AbsolutePath,
      verbose: Boolean = false
  ): Commands.ValidatedBsp = {
    val opts = if (verbose) CliOptions.default.copy(verbose = true) else CliOptions.default
    validateBsp(Commands.Bsp(protocol = BspProtocol.Tcp, cliOptions = opts))
  }

  def reportIfError(logger: BspClientLogger[RecordingLogger])(thunk: => Unit): Unit = {
    try thunk
    catch {
      case NonFatal(t) =>
        val logs = logger.underlying.getMessages().map(t => s"${t._1}: ${t._2}")
        val errorMsg = s"BSP test failed with the following logs:\n${logs.mkString("\n")}"
        System.err.println(errorMsg)
        throw t
    }
  }

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

    reportIfError(logger) {
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
          workspaceTargets.targets.map { t =>
            Assert.assertEquals(t.languageIds.sorted, List("java", "scala"))
            t.data.foreach { json =>
              ScalaBuildTarget.decodeScalaBuildTarget(json.hcursor) match {
                case Right(target) =>
                  Assert.assertTrue(
                    s"Scala bin version ${target.scalaBinaryVersion} == Scala version ${target.scalaVersion}",
                    target.scalaBinaryVersion != target.scalaVersion
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

    reportIfError(logger) {
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

  def testDependencySources(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          val btis = workspaceTargets.targets.map(_.id)
          endpoints.BuildTarget.dependencySources.request(bsp.DependencySourcesParams(btis)).map {
            case Left(error) => Left(error)
            case Right(sources) =>
              val fetchedSources = sources.items.flatMap(i => i.sources.map(_.value))
              val expectedSources = BuildLoader
                .loadSynchronously(configDir, logger.underlying)
                .flatMap(_.sources.map(s => bsp.Uri(s.underlying.toUri).value))
              val msg = s"Expected != Fetched, $expectedSources != $fetchedSources"
              val same = expectedSources.sorted.sameElements(fetchedSources.sorted)
              Right(Assert.assertTrue(msg, same))
          }
      }
    }

    reportIfError(logger) {
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
                  val classpath = p.classpath.iterator.map(i => bsp.Uri(i.toBspUri)).toList
                  val classesDir = bsp.Uri(p.classesDir.toBspUri)
                  val expectedOptions =
                    stringifyOptions(p.scalacOptions.toList, classpath, classesDir)
                  Assert.assertEquals(obtainedOptions, expectedOptions)
              }

              Right(uriOptions)
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def sourceDirectoryOf(projectBaseDir: AbsolutePath): AbsolutePath =
    projectBaseDir.resolve("src").resolve("main").resolve("scala")

  // The contents of the file (which is created during the test) contains a syntax error on purpose
  private val TestIncrementalCompilationContents =
    "package example\n\nclass UserTest {\n  List[String)](\"\")\n}"
  def testCompile(bspCmd: Commands.ValidatedBsp): Unit = {
    var receivedReports: Int = 0
    val logger = new BspClientLogger(new RecordingLogger)
    def exhaustiveTestCompile(target: bsp.BuildTarget)(implicit client: LanguageClient) = {
      def compileMainProject: Task[Either[Response.Error, bsp.CompileResult]] =
        endpoints.BuildTarget.compile.request(bsp.CompileParams(List(target.id), None, Nil))
      // Test batch compilation only for the main project
      compileMainProject.flatMap {
        case Left(e) => Task.now(Left(e))
        case Right(report) =>
          if (receivedReports != 1) {
            Task.now(
              Left(
                Response.internalError(s"Expected 1 compile report, received ${receivedReports}")
              )
            )
          } else {
            // Harcode the source directory path to avoid extra BSP invocations
            val sourceDir = sourceDirectoryOf(AbsolutePath(ProjectUris.toPath(target.id.uri)))
            val newSourceFilePath = sourceDir.resolve("TestIncrementalCompilation.scala")
            // Write a new file in the directory so that incremental compilation picks it up
            Files.write(
              newSourceFilePath.underlying,
              TestIncrementalCompilationContents.getBytes(StandardCharsets.UTF_8)
            )

            def deleteNewFile(): Unit = {
              Files.deleteIfExists(newSourceFilePath.underlying)
              ()
            }

            // Test incremental compilation with a syntax error
            val nextCompile = compileMainProject.map {
              case Left(e) =>
                val msg = e.error.message
                if (msg.contains("Compilation failed") && msg.contains(MainProject)) Right(())
                else {
                  Left(
                    Response.internalError(
                      s"Expecting 'Compilation failed' and '${MainProject}' in error msg $msg"
                    )
                  )
                }
              case Right(incrementalReport) =>
                Left(
                  Response.internalError(
                    s"Expecting incremental compilation error, received ${incrementalReport}")
                )
            }

            nextCompile
              .doOnFinish(_ => Task(deleteNewFile())) // Delete the file regardless of the result
              .flatMap {
                case Left(e) => Task.now(Left(e))
                case Right(_) =>
                  // Lastly, let's compile the project after deleting the new file
                  deleteNewFile()
                  compileMainProject.map {
                    case Left(e) => Left(e)
                    case Right(result) => Right(result)
                    /*
                      // Uncomment whenever we receive a report on the third no-op
                      if (receivedReports == 3) Right(result)
                      else {
                        Left(
                          Response.internalError(s"Expected 3, got ${receivedReports} reports")
                        )
                      }
                   */
                  }
              }
          }
      }
    }

    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.find(t => isMainProject(t.id)) match {
              case Some(t) => exhaustiveTestCompile(t)
              case None => Task.now(Left(Response.internalError(s"Missing '$MainProject'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      s.notification(endpoints.BuildTarget.compileReport) { report =>
        if (isMainProject(report.target) && receivedReports == 0) {
          // This is the batch compilation which should have a warning and no errors
          receivedReports += 1
          Assert.assertEquals(s"Warnings in $MainProject != 1", 1, report.warnings)
          Assert.assertEquals(s"Errors in $MainProject != 0", 0, report.errors)
          //Assert.assertTrue(s"Duration in $MainProject == 0", report.time != 0)
        } else if (isMainProject(report.target) && receivedReports == 1) {
          // This is the incremental compile which should have errors
          receivedReports += 1
          Assert.assertTrue(
            s"Expected errors in incremental cycle of $MainProject",
            report.errors > 0
          )
        } else if (isMainProject(report.target) && receivedReports == 2) {
          // This is the last compilation which should be successful
          // TODO(jvican): Test the initial warning is buffered and shown to the client
          ()
        } else {
          Assert.fail(s"Unexpected compilation report: $report")
        }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessagesAt(Some("info"))
      Assert.assertTrue(
        "End of compilation is not reported.",
        msgs.filter(_.startsWith("Done compiling.")).nonEmpty
      )

      // Both the start line and the start column have to be indexed by 0
      val expectedWarning =
        "[diagnostic] local val in method main is never used Range(Position(5,8),Position(5,8))"
      val warnings = logger.underlying.getMessagesAt(Some("warn"))
      Assert.assertTrue(
        s"Expected $expectedWarning, obtained $warnings.",
        warnings == List(expectedWarning)
      )

      // The syntax error has to be present as a diagnostic, not a normal log error
      val errors = logger.underlying.getMessagesAt(Some("error"))
      Assert.assertTrue(
        "Syntax error is not reported as diagnostic.",
        errors.exists(_.startsWith("[diagnostic] ']' expected but ')' found."))
      )
    }
  }

  def testCompileNoOp(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.map(_.id).find(isMainProject(_)) match {
              case Some(id) =>
                endpoints.BuildTarget.compile.request(bsp.CompileParams(List(id), None, Nil)).map {
                  case Left(e) => Left(e)
                  case Right(result) => Right(result)
                }
              case None => Task.now(Left(Response.internalError(s"Missing '$MainProject'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      s.notification(endpoints.BuildTarget.compileReport) { report =>
        Assert.fail(s"Expected no-op compilation, obtained ${report}")
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest, reusePreviousState = true)(
        c => clientWork(c)
      )
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
                endpoints.BuildTarget.test.request(bsp.TestParams(List(id), None, Nil)).map {
                  case Left(e) => Left(e)
                  case Right(report) =>
                    val valid = compiledMainProject && compiledTestProject && checkTestedTargets
                    if (valid) Right(report)
                    else Left(Response.internalError("Didn't receive all compile or test reports."))
                }
              case None => Task.now(Left(Response.internalError(s"Missing '$TestProject'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed testing with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      s.notification(endpoints.BuildTarget.compileReport) { report =>
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
        }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      Assert.assertTrue(
        "Test execution did not compile the main and test projects.",
        msgs.filter(_.contains("Done compiling.")).size == 2
      )
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
                endpoints.BuildTarget.run.request(bsp.RunParams(id, None, Nil)).map {
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
              case None => Task.now(Left(Response.internalError(s"Missing '$MainProject'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed testing with $error.")))
        }
      }
    }

    val addServicesTest = { (s: Services) =>
      s.notification(endpoints.BuildTarget.compileReport) { report =>
        if (compiledMainProject)
          Assert.fail(s"Bloop compiled unexpected target: ${report}")

        val targetUri = report.target
        if (isMainProject(targetUri)) {
          compiledMainProject = true
          Assert.assertEquals(s"Warnings in $MainProject != 1", 1, report.warnings)
          Assert.assertEquals(s"Errors in $MainProject != 0", 0, report.errors)
        }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      Assert.assertTrue(
        s"Run execution did not compile $MainProject.",
        msgs.filter(_.contains("Done compiling.")).size == 1
      )
    }
  }

  type BspResponse[T] = Task[Either[Response.Error, T]]
  // Check the BSP server errors correctly on unknown and empty targets in a compile request
  def testFailedCompileOnInvalidInputs(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def expectError(request: BspResponse[bsp.CompileResult], expected: String, failMsg: String) = {
      request.flatMap {
        case Right(report) =>
          Task.now(Left(Response.parseError(s"Expecting failed compilation, received $report.")))
        case Left(e) =>
          val validError = e.error.message.contains(expected)
          if (validError) Task.now(Right(()))
          else Task.now(Left(Response.parseError(failMsg)))
      }
    }

    def clientWork(implicit client: LanguageClient) = {
      def compileParams(xs: List[bsp.BuildTargetIdentifier]): bsp.CompileParams =
        bsp.CompileParams(xs, None, Nil)
      val expected1 = "URI 'file://this-doesnt-exist' has invalid format."
      val fail1 = "The invalid format error was missed in 'this-doesnt-exist'"
      val f = new java.net.URI("file://thisdoesntexist")
      val params1 = compileParams(List(bsp.BuildTargetIdentifier(bsp.Uri(f))))

      val expected2 = "Empty build targets. Expected at least one build target identifier."
      val fail2 = "No error was thrown on empty build targets."
      val params2 = compileParams(List())

      val extraErrors = List((expected2, fail2, params2))
      val init = expectError(endpoints.BuildTarget.compile.request(params1), expected1, fail1)
      extraErrors.foldLeft(init) {
        case (acc, (expected, fail, params)) =>
          acc.flatMap {
            case Left(l) => Task.now(Left(l))
            case Right(_) =>
              expectError(endpoints.BuildTarget.compile.request(params), expected, fail)
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, allowError = true)(c => clientWork(c))
    }
  }

  @Test def TestInitializationViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testInitialization(createLocalBspCommand(configDir))
  }

  @Test def TestInitializationViaTcp(): Unit = {
    testInitialization(createTcpBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testBuildTargets(createLocalBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaTcp(): Unit = {
    testBuildTargets(createTcpBspCommand(configDir))
  }

  @Test def TestDependencySourcesViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testDependencySources(createLocalBspCommand(configDir))
  }

  @Test def TestDependencySourcesViaTcp(): Unit = {
    testDependencySources(createTcpBspCommand(configDir))
  }

  @Test def TestScalacOptionsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testScalacOptions(createLocalBspCommand(configDir))
  }

  @Test def TestScalacOptionsViaTcp(): Unit = {
    testScalacOptions(createTcpBspCommand(configDir))
  }

  @Test def TestCompileViaLocal(): Unit = {
    if (!BspServer.isWindows) {
      testCompile(createLocalBspCommand(configDir))
      testCompileNoOp(createLocalBspCommand(configDir))
    }
  }

  @Test def TestCompileViaTcp(): Unit = {
    testCompile(createTcpBspCommand(configDir, verbose = true))
    testCompileNoOp(createTcpBspCommand(configDir, verbose = true))
  }

  @Test def TestTestViaLocal(): Unit = {
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
  }

  @Test def TestFailedCompileViaLocal(): Unit = {
    if (!BspServer.isWindows) testFailedCompileOnInvalidInputs(createLocalBspCommand(configDir))
  }

  @Test def TestFailedCompileViaTcp(): Unit = {
    testFailedCompileOnInvalidInputs(createTcpBspCommand(configDir))
  }
}
