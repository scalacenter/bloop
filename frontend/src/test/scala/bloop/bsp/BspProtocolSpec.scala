package bloop.bsp

import java.nio.file.Files

import bloop.Project
import bloop.cli.validation.Validate
import bloop.cli.{BspProtocol, Commands}
import bloop.engine.Run
import bloop.io.AbsolutePath
import bloop.tasks.TestUtil
import bloop.logging.{RecordingLogger, Slf4jAdapter}
import ch.epfl.`scala`.bsp.schema.WorkspaceBuildTargetsRequest
import org.junit.Test
import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.bsp.schema.{BuildTargetIdentifier, CompileParams, CompileReport, DependencySources, DependencySourcesItem, DependencySourcesParams, ScalacOptionsParams}
import junit.framework.Assert
import monix.eval.Task
import org.langmeta.jsonrpc.{Response => JsonRpcResponse}
import org.langmeta.lsp.LanguageClient

import scala.util.control.NonFatal

class BspProtocolSpec {
  private final val configDir = AbsolutePath(TestUtil.getBloopConfigDir("utest"))
  private final val cwd = AbsolutePath(TestUtil.getBaseFromConfigDir(configDir.underlying))
  private final val tempDir = Files.createTempDirectory("temp-sockets")
  tempDir.toFile.deleteOnExit()

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

  def createTcpBspCommand(configDir: AbsolutePath): Commands.ValidatedBsp = {
    validateBsp(Commands.Bsp(protocol = BspProtocol.Tcp))
  }

  def reportIfError(logger: Slf4jAdapter[RecordingLogger])(thunk: => Unit): Unit = {
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
    val logger = new Slf4jAdapter(new RecordingLogger)
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
      Assert.assertEquals(msgs.count(_.contains(BuildInitialize)), 10)
      Assert.assertEquals(msgs.count(_.contains(BuildInitialized)), 10)
      Assert.assertEquals(msgs.count(_.contains(CompleteHandshake)), 10)
    }
  }

  def testBuildTargets(bsp: Commands.ValidatedBsp): Unit = {
    val logger = new Slf4jAdapter(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest()).map {
        case Right(workspaceTargets) =>
          Right(Assert.assertEquals(workspaceTargets.targets.size, 8))
        case Left(error) => Left(error)
      }
    }

    BspClientTest.runTest(bsp, configDir, logger)(c => clientWork(c))

    reportIfError(logger) {
      val BuildInitialize = "\"method\" : \"build/initialize\""
      val BuildInitialized = "\"method\" : \"build/initialized\""
      val BuildTargets = "\"method\" : \"workspace/buildTargets\""
      val msgs = logger.underlying.getMessages.map(_._2)
      Assert.assertEquals(msgs.count(_.contains(BuildInitialize)), 1)
      Assert.assertEquals(msgs.count(_.contains(BuildInitialized)), 1)
      Assert.assertEquals(msgs.count(_.contains(BuildTargets)), 1)
    }
  }

  def testDependencySources(bsp: Commands.ValidatedBsp): Unit = {
    val logger = new Slf4jAdapter(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          val btis = workspaceTargets.targets.flatMap(_.id.toList)
          endpoints.BuildTarget.dependencySources.request(DependencySourcesParams(btis)).map {
            case Left(error) => Left(error)
            case Right(sources) =>
              val fetchedSources = sources.items.flatMap(_.uri)
              val expectedSources = Project
                .eagerLoadFromDir(configDir, logger.underlying)
                .flatMap(_.sources.map(_.toBspUri))
              val msg = s"Expected != Fetched, $expectedSources != $fetchedSources"
              val same = expectedSources.sorted.sameElements(fetchedSources.sorted)
              Right(Assert.assertTrue(msg, same))
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bsp, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testScalacOptions(bsp: Commands.ValidatedBsp): Unit = {
    def stringify(xs: Seq[String]) = xs.sorted.mkString(";")
    def stringifyOptions(
        scalacOptions0: Seq[String],
        classpath0: Seq[String],
        classesDir: String
    ): String = {
      val scalacOptions = stringify(scalacOptions0)
      val classpath = stringify(classpath0)
      s"""StringifiedScalacOption($scalacOptions, $classpath, $classesDir)"""
    }

    val logger = new Slf4jAdapter(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest()).flatMap {
        case Left(error) => Task.now(Left(error))
        case Right(workspaceTargets) =>
          val btis = workspaceTargets.targets.flatMap(_.id.toList)
          endpoints.BuildTarget.scalacOptions.request(ScalacOptionsParams(btis)).map {
            case Left(error) => Left(error)
            case Right(options) =>
              val optionItems0 = options.items
              val optionItems = {
                if (optionItems0.exists(_.target.isEmpty)) {
                  Left(JsonRpcResponse.internalError(
                    s"A scalac option item doesn't map to a target! $optionItems0"))
                } else Right(optionItems0.map(i => (i.target.get.uri, i)).sortBy(_._1))
              }

              optionItems.map { uriOptions =>
                val expectedUriOptions = Project
                  .eagerLoadFromDir(configDir, logger.underlying)
                  .map(p => (ProjectUris.toUri(p.baseDirectory, p.name).toString, p))
                  .sortBy(_._1)

                Assert
                  .assertEquals("Size of options differ", uriOptions.size, expectedUriOptions.size)
                uriOptions.zip(expectedUriOptions).foreach {
                  case ((obtainedUri, opts), (expectedUri, p)) =>
                    Assert.assertEquals(obtainedUri, expectedUri)
                    val obtainedOptions =
                      stringifyOptions(opts.options, opts.classpath, opts.classDirectory)
                    val classpath = p.classpath.iterator.map(_.toBspUri).toList
                    val expectedOptions =
                      stringifyOptions(p.scalacOptions.toList, classpath, p.classesDir.toBspUri)
                    Assert.assertEquals(obtainedOptions, expectedOptions)
                }
              }
          }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bsp, configDir, logger)(c => clientWork(c))
      ()
    }
  }

  def testCompile(bsp: Commands.ValidatedBsp): Unit = {
    val logger = new Slf4jAdapter(new RecordingLogger)
    def clientWork(implicit client: LanguageClient) = {
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest()).flatMap { ts =>
        ts match {
          case Right(workspaceTargets) =>
            // This will fail if `testBuildTargets` fails too, so let's not handle errors.
            val params = CompileParams(List(workspaceTargets.targets.head.id.get))
            endpoints.BuildTarget.compile.request(params)
          case Left(error) => sys.error(s"Target request failed with $error.")
        }
      }
    }

    reportIfError(logger) {
      BspClientTest.runTest(bsp, configDir, logger)(c => clientWork(c))
      // Make sure that the compilation is logged back to the client via logs in stdout
      val msgs = logger.underlying.getMessages.iterator.filter(_._1 == "info").map(_._2).toList
      Assert.assertTrue("End of compilation is not reported.", msgs.contains("Done compiling."))
    }
  }

  type BspResponse[T] = Task[Either[JsonRpcResponse.Error, T]]
  def testFailedCompile(bsp: Commands.ValidatedBsp): Unit = {
    val logger = new Slf4jAdapter(new RecordingLogger)
    def expectError(request: BspResponse[CompileReport], expected: String, failMsg: String) = {
      request.flatMap {
        case Right(report) =>
          Task.now(
            Left(JsonRpcResponse.parseError(s"Expecting failed compilation, received $report.")))
        case Left(e) =>
          val validError = e.error.message.contains(expected)
          if (validError) Task.now(Right(()))
          else Task.now(Left(JsonRpcResponse.parseError(failMsg)))
      }
    }

    def clientWork(implicit client: LanguageClient) = {
      val expected1 = "URI 'file://this-doesnt-exist' has invalid format."
      val fail1 = "The invalid format error was missed in 'this-doesnt-exist'"
      val params1 = CompileParams(List(BuildTargetIdentifier("file://this-doesnt-exist")))

      val expected2 = "URI cannot be empty."
      val fail2 = "The invalid format error was missed in empty URI."
      val params2 = CompileParams(List(BuildTargetIdentifier("")))

      val expected3 = "Empty build targets. Expected at least one build target identifier."
      val fail3 = "No error was thrown on empty build targets."
      val params3 = CompileParams(List())

      val extraErrors = List(
        (expected2, fail2, params2),
        (expected3, fail3, params3)
      )

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
      BspClientTest.runTest(bsp, configDir, logger)(c => clientWork(c))
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
    if (!BspServer.isWindows) testCompile(createLocalBspCommand(configDir))
  }

  @Test def TestCompileViaTcp(): Unit = {
    testCompile(createTcpBspCommand(configDir))
  }

  @Test def TestFailedCompileViaLocal(): Unit = {
    if (!BspServer.isWindows) testFailedCompile(createLocalBspCommand(configDir))
  }

  @Test def TestFailedCompileViaTcp(): Unit = {
    testFailedCompile(createTcpBspCommand(configDir))
  }
}
