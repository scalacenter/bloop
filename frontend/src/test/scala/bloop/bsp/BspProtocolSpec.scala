package bloop.bsp

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap

import bloop.cli.validation.Validate
import bloop.cli.{BspProtocol, CliOptions, Commands, CommonOptions}
import bloop.data.Project
import bloop.engine.{BuildLoader, Run}
import bloop.io.{AbsolutePath, RelativePath}
import bloop.tasks.TestUtil
import bloop.logging.{BspClientLogger, RecordingLogger}
import org.junit.Test
import ch.epfl.scala.bsp
import ch.epfl.scala.bsp.{BuildTargetIdentifier, ScalaBuildTarget, endpoints}
import junit.framework.Assert
import monix.eval.Task

import scala.meta.jsonrpc.{LanguageClient, Response, Services}

class BspProtocolSpec {
  private final val configDir = AbsolutePath(TestUtil.getBloopConfigDir("cross-test-build-0.6"))
  private final val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

  private final val MainProject = "test-project"
  private final val TestProject = "test-project-test"

  private final val MainJsProject = "test-projectJS"
  private final val TestJsProject = "test-projectJS-test"

  // Load the current build associated with the configuration directory to test project metadata
  private final val crossTestBuild = BuildLoader.loadSynchronously(configDir, new RecordingLogger)
  private val mainProject = crossTestBuild
    .find(_.name == MainProject)
    .getOrElse(sys.error(s"Missing main project $MainProject in $crossTestBuild"))
  Files.createDirectories(mainProject.baseDirectory.underlying)
  private val testProject = crossTestBuild
    .find(_.name == TestProject)
    .getOrElse(sys.error(s"Missing main project $TestProject in $crossTestBuild"))
  Files.createDirectories(testProject.baseDirectory.underlying)

  def isMainProject(targetUri: BuildTargetIdentifier): Boolean =
    targetUri.uri == mainProject.bspUri
  def isTestProject(targetUri: BuildTargetIdentifier): Boolean =
    targetUri.uri == testProject.bspUri

  def validateBsp(bspCommand: Commands.Bsp, configDir: AbsolutePath): Commands.ValidatedBsp = {
    val baseDir = AbsolutePath(configDir.underlying.getParent)
    Validate.bsp(bspCommand, BspServer.isWindows) match {
      case Run(bsp: Commands.ValidatedBsp, _) =>
        BspClientTest.setupBspCommand(bsp, baseDir, configDir)
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
      ),
      configDir
    )
  }

  def createTcpBspCommand(
      configDir: AbsolutePath,
      verbose: Boolean = false
  ): Commands.ValidatedBsp = {
    val opts = if (verbose) CliOptions.default.copy(verbose = true) else CliOptions.default
    validateBsp(Commands.Bsp(protocol = BspProtocol.Tcp, cliOptions = opts), configDir)
  }

  def reportIfError(logger: BspClientLogger[RecordingLogger])(thunk: => Unit): Unit = {
    try thunk
    catch {
      case t: Throwable =>
        val logs = logger.underlying.getMessages().map(t => s"${t._1}: ${t._2}")
        val errorMsg = s"BSP test failed with the following logs:\n${logs.mkString("\n")}"
        val insideCI =
          Option(System.getProperty("CI")).orElse(Option(System.getProperty("JENKINS_HOME")))
        if (insideCI.isDefined) System.err.println(errorMsg)
        else {
          val tempFile = Files.createTempFile("bsp", ".log")
          System.err.println(s"Writing bsp diagnostics logs to ${tempFile}")
          Files.write(tempFile, errorMsg.getBytes(StandardCharsets.UTF_8))
        }
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
                    case Some(MainProject) => bsp.ScalaPlatform.Jvm
                    case Some(TestProject) => bsp.ScalaPlatform.Jvm
                    case Some(MainJsProject) => bsp.ScalaPlatform.Js
                    case Some(TestJsProject) => bsp.ScalaPlatform.Js
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

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      val recursiveError = "Fatal recursive dependency detected in 'g': List(g, g)"
      val errors = logger.underlying.getMessagesAt(Some("error"))
      Assert.assertEquals(1, errors.count(_.contains(recursiveError)))
    }
  }

  def testSources(bspCmd: Commands.ValidatedBsp): Unit = {
    def testSourcePerTarget(bti: BuildTargetIdentifier, project: Project)(
        implicit client: LanguageClient) = {
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
          workspaceTargets.targets.find(_.displayName == Some(MainProject)) match {
            case Some(mainTarget) =>
              testSourcePerTarget(mainTarget.id, mainProject).flatMap {
                case Left(e) => Task.now(Left(e))
                case Right(_) =>
                  workspaceTargets.targets.find(_.displayName == Some(TestProject)) match {
                    case Some(testTarget) => testSourcePerTarget(testTarget.id, testProject)
                    case None =>
                      Task.now(
                        Left(
                          Response.internalError(s"Missing test project in ${workspaceTargets}")))
                  }
              }
            case None =>
              Task.now(Left(Response.internalError(s"Missing main project in ${workspaceTargets}")))
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testDependencySources(bspCmd: Commands.ValidatedBsp): Unit = {
    def testSourcePerTarget(bti: BuildTargetIdentifier, project: Project)(
        implicit client: LanguageClient) = {
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
          workspaceTargets.targets.find(_.displayName == Some(MainProject)) match {
            case Some(mainTarget) =>
              testSourcePerTarget(mainTarget.id, mainProject).flatMap {
                case Left(e) => Task.now(Left(e))
                case Right(_) =>
                  workspaceTargets.targets.find(_.displayName == Some(TestProject)) match {
                    case Some(testTarget) => testSourcePerTarget(testTarget.id, testProject)
                    case None =>
                      Task.now(
                        Left(
                          Response.internalError(s"Missing test project in ${workspaceTargets}")))
                  }
              }
            case None =>
              Task.now(Left(Response.internalError(s"Missing main project in ${workspaceTargets}")))
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
                  val classpath =
                    p.compilationClasspath.iterator.map(i => bsp.Uri(i.toBspUri)).toList
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
  def testSourceDirectoryOf(projectBaseDir: AbsolutePath): AbsolutePath =
    projectBaseDir.resolve("src").resolve("test").resolve("scala")

  // The contents of the file (which is created during the test) contains a syntax error on purpose
  private val scalaCodeWithSyntacticError =
    "package example\n\nclass SyntacticTest { List[String)](\"\") }"

  private val scalaCodeWithSemanticError =
    "package example\n\nclass OneTest { val i: Int = \"\" }"

  private val scalaCodeWithSemanticError2 =
    "package example\n\nclass SecondTest { def bar: Int = { val d = 2; 2 }; val s: String = 1}"

  private val scalaCodeWithOneWarning =
    "package example\n\nclass WarningTest { def foo = { val a = 1; List[String](\"\") } }"

  private val scalaCodeWithNoProblems =
    "package example\n\nclass OKTest { def foo = { List[String](\"\") } }"

  private val secondScalaCodeWithNoProblems =
    "package example\n\nclass OK2Test { def foo = { List[String](\"\") } }"

  // The contents of the file adding a new subclass to force a 2nd cycle of incremental compilation
  private val scalaCodeDeclaringNewSubclass =
    "package hello\n\nclass ForceSubclassRecompilation extends JUnitTest"

  private def checkCompileStart(task: bsp.TaskStartParams, compileTask: bsp.CompileTask): Unit = {
    Assert.assertTrue(
      s"Task id is empty in ${task}",
      task.taskId.id.nonEmpty
    )
    Assert.assertTrue(
      "Event time in task start is defined",
      task.eventTime.isDefined
    )
    if (isMainProject(compileTask.target)) {
      val msg = s"Compiling '$MainProject'"
      Assert.assertTrue(
        s"Message '${task.message}' doesn't start with ${msg}",
        task.message.exists(_.startsWith(msg))
      )
      Assert.assertTrue(
        s"Expected '$MainProject' in ${compileTask.target.uri}",
        compileTask.target.uri.value.contains(MainProject)
      )
    } else if (isTestProject(compileTask.target)) {
      val msg = s"Compiling '$TestProject'"
      Assert.assertTrue(
        s"Message '${task.message}' doesn't start with ${msg}",
        task.message.exists(_.startsWith(msg))
      )
      Assert.assertTrue(
        s"Expected '$TestProject' in ${compileTask.target.uri}",
        compileTask.target.uri.value.contains(TestProject)
      )
    } else Assert.fail(s"Unknown target ${compileTask.target} in ${task}")
  }

  type Result = Either[Response.Error, bsp.CompileResult]
  private def composeCompilation(
      prev: Result,
      newCompilation: Task[Result]
  ): Task[Result] = prev.fold(e => Task.now(Left(e)), _ => newCompilation)

  private def io[T](thunk: => T): T = {
    // Avoid spurious CI errors by giving the OS some time to pick up changes
    val value = thunk; Thread.sleep(50); value
  }

  type DiagnosticKey = (bsp.BuildTargetIdentifier, Int) // Int = Cycle starts at 0
  private def addNotificationsHandler(
      services: Services,
      stringifiedDiagnostics: ConcurrentHashMap[DiagnosticKey, StringBuilder],
      decideCompilationCycleIndex: bsp.BuildTargetIdentifier => Int
  ): Services = {
    services.notification(endpoints.Build.publishDiagnostics) {
      case p @ bsp.PublishDiagnosticsParams(tid, btid, _, diagnostics, reset) =>
        // Add the diagnostics to the stringified diagnostics map
        val cycleIndex = decideCompilationCycleIndex(btid)
        stringifiedDiagnostics.compute(
          (btid, cycleIndex),
          (_: DiagnosticKey, builder0) => {
            val builder = if (builder0 == null) new StringBuilder() else builder0
            val baseDir = {
              // TODO(jvican): Fix when https://github.com/scalacenter/bloop/issues/724 is done
              val wp = CommonOptions.default.workingPath
              if (wp.underlying.endsWith("frontend")) wp
              else wp.resolve("frontend")
            }

            // Make the relative path compatible with unix for tests to pass in Windows
            Assert.assertTrue(
              s"URI ${tid.uri} does not start with valid 'file:///'",
              tid.uri.value.startsWith("file:///")
            )
            val relativePath = AbsolutePath(tid.uri.toPath).toRelative(baseDir)
            val canonical = relativePath.toString.replace(File.separatorChar, '/')
            val report = diagnostics
              .map(_.toString.replace("\n", " ").replace(System.lineSeparator, " "))
            builder
              .++=(s"#${cycleIndex + 1}: $canonical\n")
              .++=(s"  -> $report\n")
              .++=(s"  -> reset = $reset\n")
          }
        )
        ()
    }
  }

  private def findDiagnosticsReportUntil(
      btid: BuildTargetIdentifier,
      stringifiedDiagnostics: ConcurrentHashMap[DiagnosticKey, StringBuilder],
      compilationNumber: Int
  ): String = {
    val builder = new StringBuilder()
    for (i <- 1 to compilationNumber) {
      val result = stringifiedDiagnostics.get((btid, i - 1))
      if (result == null) {
        builder.++=(s"#$i: No diagnostics for $btid\n")
      } else {
        builder.++=(result.mkString)
      }
    }
    builder.mkString.stripLineEnd
  }

  /**
   * This test checks the behavior of compilation is as comprehensive and well-specified as it can.
   *
   * High-level invariants we want to enforce to be compliant with BSP:
   *
   * - Send `build/taskFinish` if a compilation fails or the user cancels.
   * - Send one `build/taskFinish` per compiled target (even transitively).
   * - Send one `build/taskFinish` in compilation even if multiple incremental cycles.
   * - Send `build/taskStart` for all successful and failed requests.
   * - Don't send a `build/task*` notifications if incremental compilation is enabled.
   * - Don't encode compilation errors as a json protocol error.
   *
   * @param bspCmd The command that we must use to connect to the bsp session.
   */
  def testCompile(bspCmd: Commands.ValidatedBsp): Unit = {
    var mainReportsIndex: Int = 0
    var testReportsIndex: Int = 0
    val compilationResults = new StringBuilder()
    val logger = new BspClientLogger(new RecordingLogger)

    def exhaustiveTestCompile(target: bsp.BuildTarget)(implicit client: LanguageClient) = {
      def compileProject: Task[Either[Response.Error, bsp.CompileResult]] = {
        endpoints.BuildTarget.compile.request(bsp.CompileParams(List(target.id), None, None)).map {
          case Right(r) => compilationResults.++=(s"${r.statusCode}"); Right(r)
          case Left(e) => Left(e)
        }
      }

      val sourceDir = sourceDirectoryOf(AbsolutePath(ProjectUris.toPath(target.id.uri)))
      Files.createDirectories(sourceDir.underlying)
      val testIncrementalCompilationFile =
        sourceDir.resolve("TestIncrementalCompilation.scala")
      val testIncrementalCompilationFile2 =
        sourceDir.resolve("TestIncrementalCompilation2.scala")
      val testWarningFile = sourceDir.resolve("TestWarning.scala")

      val testSourceDir =
        testSourceDirectoryOf(AbsolutePath(ProjectUris.toPath(target.id.uri)))
      Files.createDirectories(testSourceDir.underlying)
      val junitTestSubclassFile = testSourceDir.resolve("JUnitTestSubclass.scala")

      def deleteJUnitTestSubclassFile(): Boolean =
        Files.deleteIfExists(junitTestSubclassFile.underlying)
      def deleteTestIncrementalCompilationFiles(): Unit = {
        Files.deleteIfExists(testWarningFile.underlying)
        Files.deleteIfExists(testIncrementalCompilationFile.underlying)
        Files.deleteIfExists(testIncrementalCompilationFile2.underlying)
        ()
      }

      val driver = for {
        // #1: Full compile just works
        result1 <- compileProject
        _ = io {
          // Write three new files, two of which contain semantic errors
          Files.write(
            testWarningFile.underlying,
            scalaCodeWithOneWarning.getBytes(StandardCharsets.UTF_8)
          )

          Files.write(
            testIncrementalCompilationFile.underlying,
            scalaCodeWithSemanticError.getBytes(StandardCharsets.UTF_8)
          )

          Files.write(
            testIncrementalCompilationFile2.underlying,
            scalaCodeWithSemanticError2.getBytes(StandardCharsets.UTF_8)
          )
        }
        // #2: Failed incremental compile with two errors, one per file
        result2 <- composeCompilation(result1, compileProject)
        _ = io {
          Files.write(
            testWarningFile.underlying,
            secondScalaCodeWithNoProblems.getBytes(StandardCharsets.UTF_8)
          )

          Files.write(
            testIncrementalCompilationFile.underlying,
            scalaCodeWithNoProblems.getBytes(StandardCharsets.UTF_8)
          )
        }
        // #3: Failed incremental compile with only one error
        result3 <- composeCompilation(result2, compileProject)
        _ = io(deleteTestIncrementalCompilationFiles())
        // #4: No-op compile (analysis is the same as in the round before the errors)
        result4 <- composeCompilation(result3, compileProject)
        _ = io {
          // Write a syntactic error in the same file as before
          Files.write(
            testIncrementalCompilationFile.underlying,
            scalaCodeWithSyntacticError.getBytes(StandardCharsets.UTF_8)
          )
        }
        // #5: Failed incremental compile again with previous file
        result5 <- composeCompilation(result4, compileProject)
        _ = io {
          Files.write(
            testIncrementalCompilationFile.underlying,
            scalaCodeWithNoProblems.getBytes(StandardCharsets.UTF_8)
          )
        }
        // #6: Successful incremental compile after writing the right contents to the file
        result6 <- composeCompilation(result5, compileProject)
        _ = io(deleteTestIncrementalCompilationFiles())
        // #7: No-op compile again
        result7 <- composeCompilation(result6, compileProject)
        _ = io {
          // Write a new file in the test project directory now
          Files.write(
            junitTestSubclassFile.underlying,
            scalaCodeDeclaringNewSubclass.getBytes(StandardCharsets.UTF_8)
          )
        }
        // #8: Successful incremental compile with two cycles
        result8 <- composeCompilation(result7, compileProject)
        _ = io(deleteJUnitTestSubclassFile())
        // #9: Successful incremental compile with same contents as first round
        result9 <- composeCompilation(result8, compileProject)
      } yield result9

      val deleteAllResources =
        Task { deleteTestIncrementalCompilationFiles(); deleteJUnitTestSubclassFile(); () }
      driver.doOnCancel(deleteAllResources).doOnFinish(_ => deleteAllResources)
    }

    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.find(t => isTestProject(t.id)) match {
              case Some(t) => exhaustiveTestCompile(t)
              case None => Task.now(Left(Response.internalError(s"Missing '$MainProject'")))
            }
          case Left(error) =>
            Task.now(Left(Response.internalError(s"Target request failed with $error.")))
        }
      }
    }

    val startedTask = scala.collection.mutable.HashSet[bsp.TaskId]()
    val stringifiedDiagnostics = new ConcurrentHashMap[DiagnosticKey, StringBuilder]()

    val addServicesTest = { (s: Services) =>
      val services = s
        .notification(endpoints.Build.taskStart) { taskStart =>
          taskStart.dataKind match {
            case Some(bsp.TaskDataKind.CompileTask) =>
              // Add the task id to the list of started task so that we check them in `taskFinish`
              if (startedTask.contains(taskStart.taskId))
                Assert.fail(s"Task id ${taskStart.taskId} is already added!")
              else startedTask.add(taskStart.taskId)

              val json = taskStart.data.get
              bsp.CompileTask.decodeCompileTask(json.hcursor) match {
                case Left(failure) =>
                  Assert.fail(s"Decoding `$json` as a scala build target failed: $failure")
                case Right(compileTask) =>
                  checkCompileStart(taskStart, compileTask)
              }
            case _ => Assert.fail(s"Got an unknown task start $taskStart")
          }
        }
        .notification(endpoints.Build.taskFinish) { taskFinish =>
          taskFinish.dataKind match {
            case Some(bsp.TaskDataKind.CompileReport) =>
              Assert.assertTrue(
                s"Task id ${taskFinish.taskId} has not been sent by `build/taskStart`",
                startedTask.contains(taskFinish.taskId)
              )

              val json = taskFinish.data.get
              bsp.CompileReport.decodeCompileReport(json.hcursor) match {
                case Right(report) =>
                  if (isMainProject(report.target)) {
                    mainReportsIndex += 1
                  } else if (isTestProject(report.target)) {
                    testReportsIndex += 1
                  } else {
                    Assert.fail(s"Unexpected compilation target in: $report")
                  }
                case Left(failure) =>
                  Assert.fail(s"Decoding `$json` as a scala build target failed: $failure")
              }
            case _ => Assert.fail(s"Got an unknown task finish $taskFinish")
          }
        }

      addNotificationsHandler(
        services,
        stringifiedDiagnostics,
        (btid: bsp.BuildTargetIdentifier) => {
          val isTestProject = btid.uri == testProject.bspUri
          if (isTestProject) testReportsIndex else mainReportsIndex
        }
      )
    }

    reportIfError(logger) {
      BspClientTest.runTest(
        bspCmd,
        configDir,
        logger,
        addServicesTest,
        addDiagnosticsHandler = false
      )(c => clientWork(c))

      val totalMainCompilations = 9
      Assert.assertEquals(
        s"Mismatch in total number of compilations for $MainProject",
        totalMainCompilations,
        mainReportsIndex
      )

      // There are less compilations for test because main fails to compile several times
      val totalTestCompilations = 6
      Assert.assertEquals(
        s"Mismatch in total number of compilations for $TestProject",
        totalTestCompilations,
        testReportsIndex
      )

      val mainTarget = bsp.BuildTargetIdentifier(mainProject.bspUri)
      val expectedMainDiagnostics =
        s"""#1: src/test/resources/cross-test-build-0.6/test-project/shared/src/main/scala/hello/App.scala
           |  -> List(Diagnostic(Range(Position(5,8),Position(5,8)),Some(Warning),None,None,local val in method main is never used,None))
           |  -> reset = true
           |#2: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List(Diagnostic(Range(Position(2,29),Position(2,29)),Some(Error),None,None,type mismatch;  found   : String("")  required: Int,None))
           |  -> reset = true
           |#2: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation2.scala
           |  -> List(Diagnostic(Range(Position(2,68),Position(2,68)),Some(Error),None,None,type mismatch;  found   : Int(1)  required: String,None))
           |  -> reset = true
           |#2: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestWarning.scala
           |  -> List(Diagnostic(Range(Position(2,36),Position(2,36)),Some(Warning),None,None,local val in method foo is never used,None))
           |  -> reset = true
           |#3: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation2.scala
           |  -> List(Diagnostic(Range(Position(2,68),Position(2,68)),Some(Error),None,None,type mismatch;  found   : Int(1)  required: String,None))
           |  -> reset = true
           |#3: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List()
           |  -> reset = true
           |#3: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestWarning.scala
           |  -> List()
           |  -> reset = true
           |#4: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation2.scala
           |  -> List()
           |  -> reset = true
           |#5: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List(Diagnostic(Range(Position(2,33),Position(2,33)),Some(Error),None,None,']' expected but ')' found.,None))
           |  -> reset = true
           |#6: src/test/resources/cross-test-build-0.6/test-project/jvm/src/main/scala/TestIncrementalCompilation.scala
           |  -> List()
           |  -> reset = true
           |#7: No diagnostics for $mainTarget
           |#8: No diagnostics for $mainTarget
           |#9: No diagnostics for $mainTarget
         """.stripMargin

      // There must only be 5 compile reports with diagnostics in the main project
      val obtainedMainDiagnostics =
        findDiagnosticsReportUntil(mainTarget, stringifiedDiagnostics, totalMainCompilations)
      TestUtil.assertNoDiff(expectedMainDiagnostics, obtainedMainDiagnostics)

      val testTarget = bsp.BuildTargetIdentifier(testProject.bspUri)
      val expectedTestDiagnostics =
        s"""#1: No diagnostics for $testTarget
           |#2: No diagnostics for $testTarget
           |#3: No diagnostics for $testTarget
           |#4: No diagnostics for $testTarget
           |#5: No diagnostics for $testTarget
           |#6: No diagnostics for $testTarget
         """.stripMargin

      // The compilation of the test project sends no diagnostics whatosever
      val obtainedTestDiagnostics =
        findDiagnosticsReportUntil(testTarget, stringifiedDiagnostics, totalTestCompilations)
      TestUtil.assertNoDiff(expectedTestDiagnostics, obtainedTestDiagnostics)
    }
  }

  // Check that we send diagnostics stored in previous analysis when a new bsp session is established
  def testFreshReportingInNoOpCompilation(bspCmd: Commands.ValidatedBsp): Unit = {
    var mainReportsIndex: Int = 0
    var testReportsIndex: Int = 0
    val logger = new BspClientLogger(new RecordingLogger)
    val startedTask = scala.collection.mutable.HashSet[bsp.TaskId]()
    val stringifiedDiagnostics = new ConcurrentHashMap[DiagnosticKey, StringBuilder]()

    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(bsp.WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            workspaceTargets.targets.map(_.id).find(isTestProject(_)) match {
              case Some(id) =>
                endpoints.BuildTarget.compile.request(bsp.CompileParams(List(id), None, None)).map {
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
      val services = s
        .notification(endpoints.Build.taskStart) { taskStart =>
          taskStart.dataKind match {
            case Some(bsp.TaskDataKind.CompileTask) =>
              // Add the task id to the list of started task so that we check them in `taskFinish`
              if (startedTask.contains(taskStart.taskId))
                Assert.fail(s"Task id ${taskStart.taskId} is already added!")
              else startedTask.add(taskStart.taskId)

              val json = taskStart.data.get
              bsp.CompileTask.decodeCompileTask(json.hcursor) match {
                case Left(failure) =>
                  Assert.fail(s"Decoding `$json` as a scala build target failed: $failure")
                case Right(compileTask) => checkCompileStart(taskStart, compileTask)
              }
            case _ => Assert.fail(s"Got an unknown task start $taskStart")
          }
        }
        .notification(endpoints.Build.taskFinish) { taskFinish =>
          taskFinish.dataKind match {
            case Some(bsp.TaskDataKind.CompileReport) =>
              Assert.assertTrue(
                s"Task id ${taskFinish.taskId} has not been sent by `build/taskStart`",
                startedTask.contains(taskFinish.taskId)
              )

              val json = taskFinish.data.get
              bsp.CompileReport.decodeCompileReport(json.hcursor) match {
                case Right(report) =>
                  if (isMainProject(report.target) && mainReportsIndex == 0) {
                    // This is the batch compilation which should have a warning and no errors
                    mainReportsIndex += 1
                    Assert.assertEquals(s"Warnings in $MainProject != 1", 1, report.warnings)
                    Assert.assertEquals(s"Errors in $MainProject != 0", 0, report.errors)
                    //Assert.assertTrue(s"Duration in $MainProject == 0", report.time != 0)
                  } else if (isTestProject(report.target) && testReportsIndex == 0) {
                    // All compilations of the test project must compile correctly
                    testReportsIndex += 1
                    Assert.assertEquals(s"Warnings in $MainProject != 0", 0, report.warnings)
                    Assert.assertEquals(s"Errors in $MainProject != 0", 0, report.errors)
                  } else {
                    Assert.fail(s"Unexpected compilation report: $report")
                  }
                case Left(failure) =>
                  Assert.fail(s"Decoding `$json` as a scala build target failed: $failure")
              }

            case _ => Assert.fail(s"Got an unknown task finish $taskFinish")
          }
        }

      addNotificationsHandler(
        services,
        stringifiedDiagnostics,
        (btid: bsp.BuildTargetIdentifier) => {
          val isTestProject = btid.uri == testProject.bspUri
          if (isTestProject) testReportsIndex else mainReportsIndex
        }
      )
    }

    reportIfError(logger) {
      BspClientTest.runTest(
        bspCmd,
        configDir,
        logger,
        addServicesTest,
        reusePreviousState = true,
        addDiagnosticsHandler = false
      )(c => clientWork(c))

      val totalMainCompilations = 1
      Assert.assertEquals(
        s"Mismatch in total number of compilations for $MainProject",
        totalMainCompilations,
        mainReportsIndex
      )

      val totalTestCompilations = 1
      Assert.assertEquals(
        s"Mismatch in total number of compilations for $TestProject",
        totalTestCompilations,
        testReportsIndex
      )

      val mainTarget = bsp.BuildTargetIdentifier(mainProject.bspUri)
      val expectedMainDiagnostics =
        s"""#1: src/test/resources/cross-test-build-0.6/test-project/shared/src/main/scala/hello/App.scala
           |  -> List(Diagnostic(Range(Position(5,8),Position(5,8)),Some(Warning),None,None,local val in method main is never used,None))
           |  -> reset = true
         """.stripMargin

      val obtainedMainDiagnostics =
        findDiagnosticsReportUntil(mainTarget, stringifiedDiagnostics, totalMainCompilations)
      TestUtil.assertNoDiff(expectedMainDiagnostics, obtainedMainDiagnostics)

      val testTarget = bsp.BuildTargetIdentifier(testProject.bspUri)
      val expectedTestDiagnostics =
        s"""#1: No diagnostics for $testTarget
         """.stripMargin

      val obtainedTestDiagnostics =
        findDiagnosticsReportUntil(testTarget, stringifiedDiagnostics, totalTestCompilations)
      TestUtil.assertNoDiff(expectedTestDiagnostics, obtainedTestDiagnostics)
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
              case None => Task.now(Left(Response.internalError(s"Missing '$TestProject'")))
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

    reportIfError(logger) {
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
              case None => Task.now(Left(Response.internalError(s"Missing '$MainProject'")))
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

    reportIfError(logger) {
      BspClientTest.runTest(bspCmd, configDir, logger, addServicesTest)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      /*      Assert.assertTrue(
        s"Run execution did not compile $MainProject.",
        msgs.filter(_.contains("Done compiling.")).size == 1
      )*/
    }
  }

  type BspResponse[T] = Task[Either[Response.Error, T]]
  // Check the BSP server errors correctly on unknown and empty targets in a compile request
  def testFailedCompileOnInvalidInputs(bspCmd: Commands.ValidatedBsp): Unit = {
    val logger = new BspClientLogger(new RecordingLogger)
    def expectError(request: BspResponse[bsp.CompileResult], expected: String, failMsg: String) = {
      request.map {
        case Right(report) =>
          Assert.assertEquals(report.statusCode, bsp.StatusCode.Error)
          if (!logger.underlying.getMessagesAt(Some("error")).exists(_.contains(expected))) {
            logger.underlying.dump()
            Assert.fail(failMsg)
          }
          Right(())
        case Left(e) => Left(e)
      }
    }

    def clientWork(implicit client: LanguageClient) = {
      def compileParams(xs: List[bsp.BuildTargetIdentifier]): bsp.CompileParams =
        bsp.CompileParams(xs, None, None)
      val expected1 = "URI 'file://thisdoesntexist' has invalid format."
      val fail1 = "The invalid format error was missed in 'thisdoesntexist'"
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

  @Test def TestBuildWithRecursiveDependenciesViaTcp(): Unit = {
    val configDir = TestUtil.createSimpleRecursiveBuild(RelativePath("bloop-config"))
    testBuildTargetsEmpty(createTcpBspCommand(configDir), configDir)
  }

  @Test def TestBuildTargetsViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testBuildTargets(createLocalBspCommand(configDir))
  }

  @Test def TestBuildTargetsViaTcp(): Unit = {
    testBuildTargets(createTcpBspCommand(configDir))
  }

  @Test def TestSourcesViaLocal(): Unit = {
    // Doesn't work with Windows at the moment, see #281
    if (!BspServer.isWindows) testSources(createLocalBspCommand(configDir))
  }

  @Test def TestSourcesViaTcp(): Unit = {
    testSources(createTcpBspCommand(configDir))
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
      testFreshReportingInNoOpCompilation(createLocalBspCommand(configDir))
    }
  }

  @Test def TestCompileViaTcp(): Unit = {
    testCompile(createTcpBspCommand(configDir))
    testFreshReportingInNoOpCompilation(createTcpBspCommand(configDir))
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
    if (!BspServer.isWindows) testFailedCompileOnInvalidInputs(createLocalBspCommand(configDir))
  }

  @Test def TestFailedCompileViaTcp(): Unit = {
    testFailedCompileOnInvalidInputs(createTcpBspCommand(configDir))
  }
}
