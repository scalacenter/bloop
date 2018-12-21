package bloop.launcher

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.launcher.core.{AvailableAt, Feedback, Installer, Shell}
import bloop.launcher.util.Environment
import bloop.logging.{BspClientLogger, RecordingLogger}
import bloop.tasks.TestUtil
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import org.junit.{After, Assert, Test}
import sbt.internal.util.MessageOnlyException

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc._
import scala.util.control.NonFatal

class LauncherSpec extends AbstractLauncherSpec {
  // Update the bsp version whenever we change the bloop version
  private final val bspVersion = "2.0.0-M1"
  private final val bloopVersion = "1.1.2"
  private final val bloopServerPort = 9012
  private final val bloopDependency = s"ch.epfl.scala:bloop-frontend_2.12:${bloopVersion}"
  private final val shellWithPython = new Shell(true, true)
  private final val shellWithNoPython = new Shell(true, false)

  case class LauncherRun(launcher: LauncherMain, output: ByteArrayOutputStream) {
    def logs: List[String] =
      (new String(output.toByteArray, StandardCharsets.UTF_8)).split(System.lineSeparator()).toList
  }

  def setUpLauncher(
      in: InputStream,
      out: OutputStream,
      shell: Shell,
      startedServer: Promise[Unit] = Promise[Unit]()
  )(
      launcherLogic: LauncherRun => Unit
  ): Unit = {
    import java.io.ByteArrayOutputStream
    import java.io.PrintStream
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos, true, "UTF-8")
    val port = Some(bloopServerPort)
    val launcher = new LauncherMain(in, out, ps, StandardCharsets.UTF_8, shell, port, startedServer)
    val run = new LauncherRun(launcher, baos)

    try launcherLogic(run)
    catch {
      case NonFatal(t) =>
        println("Test case failed with the following logs: ", System.err)
        printQuoted(run.logs.mkString(System.lineSeparator()), System.err)
        throw t
    } finally {
      if (ps != null) ps.close()
    }
  }

  @Test
  def testSystemPropertiesMockingWork(): Unit = {
    val parentDir = this.binDirectory.getParent
    parentDir.toFile.deleteOnExit()
    Assert.assertEquals(parentDir, Environment.cwd.getParent)
    Assert.assertEquals(parentDir, Environment.homeDirectory.getParent)
  }

  @Test
  def failIfEmptyArguments(): Unit = {
    setUpLauncher(System.in, System.out, shellWithPython) { run =>
      val status = run.launcher.cli(Array())
      Assert.assertTrue(
        "Expected failed bloop launcher",
        status == LauncherStatus.FailedToParseArguments
      )

      Assert.assertTrue(
        s"Missing '${Feedback.NoBloopVersion}'",
        run.logs.exists(_.contains(Feedback.NoBloopVersion))
      )
    }
  }

  @Test
  def failForOutdatedBloopVersion(): Unit = {
    setUpLauncher(System.in, System.out, shellWithPython) { run =>
      val args = Array("1.0.0")
      val status = run.launcher.cli(args)
      Assert.assertTrue(
        s"Expected failure in launcher run for ${args}",
        status == LauncherStatus.FailedToInstallBloop
      )
    }
  }

  @Test
  def checkThatPythonIsInClasspath(): Unit = {
    // Python must always be in the classpath in order to run these tests, if this fails install it
    Assert.assertTrue(shellWithPython.isPythonInClasspath)
  }

  @Test
  def dontDetectSystemBloop(): Unit = {
    setUpLauncher(System.in, System.out, shellWithPython) { setup =>
      // We should not detect the server state unless we have installed it via the launcher
      val status = setup.launcher.detectServerState(bloopVersion)
      Assert.assertEquals(s"Expected missing bloop binary, found status ${status}", None, status)
    }
  }

  /**
   * Defines a test that starts from an environment where bloop is installed and the
   * server is not running. The following invariants are tested:
   *
   * 1. A bsp launcher execution is executed. This run starts a bloop server and then
   *    uses the nailgun script to open a bsp connection. The bsp initialization handhake
   *    completes successfully.
   *
   * 2. Another bsp launcher execution is executed, but this time the server is running
   *    in the background. This run detects the server and uses the nailgun script to
   *    open a bsp connection. The bsp initialization handhake completes successfully.
   */
  @Test
  def testInstallationAndRunBspServer(): Unit = {
    setUpLauncher(System.in, System.out, shellWithPython) { run =>
      // Install the launcher via `install.py`, which is the preferred installation method
      val launcher = run.launcher
      val state = Installer.installBloopBinaryInHomeDir(
        this.binDirectory,
        Environment.defaultBloopDirectory,
        bloopVersion,
        launcher.out,
        launcher.detectServerState(_),
        launcher.shell
      )

      // We should detect the bloop binary in the place where we installed it!
      val bloopDir = Environment.defaultBloopDirectory.resolve("bloop")
      state match {
        case Some(AvailableAt(binary)) if binary.headOption.exists(_.contains(bloopDir.toString)) =>
          // After installing, let's run the launcher in an environment where bloop is available
          val result1 = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)

          val expectedLogs1 = List(
            Feedback.DetectedBloopinstallation,
            Feedback.startingBloopServer(Nil),
            Feedback.openingBspConnection(Nil)
          )

          val prohibitedLogs1 = List(
            Feedback.installingBloop(bloopVersion),
            Feedback.SkippingFullInstallation,
            Feedback.UseFallbackInstallation,
            Feedback.resolvingDependency(bloopDependency),
          )

          result1.throwIfFailed
          assertLogsContain(expectedLogs1, result1.launcherLogs)

          val expectedLogs2 = List(
            Feedback.DetectedBloopinstallation,
            Feedback.openingBspConnection(Nil)
          )

          val prohibitedLogs2 = List(
            Feedback.installingBloop(bloopVersion),
            Feedback.SkippingFullInstallation,
            Feedback.UseFallbackInstallation,
            Feedback.resolvingDependency(bloopDependency),
            Feedback.startingBloopServer(Nil),
          )

          // Now, the server should be running, check we can open a connection again
          val result2 = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)
          result2.throwIfFailed
          assertLogsContain(expectedLogs2, result2.launcherLogs, prohibitedLogs2)
        case _ => Assert.fail(s"Obtained unexpected ${state}")
      }
    }
  }

  // We constrain # of threads to guarantee no hanging threads/resources
  private val bspScheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution
  )

  def startBspInitializeHandshake[T](
      in: InputStream,
      out: OutputStream,
      logger: BspClientLogger[_]
  )(runEndpoints: LanguageClient => Task[Either[Response.Error, T]]): Task[T] = {
    import ch.epfl.scala.bsp
    import ch.epfl.scala.bsp.endpoints
    implicit val lsClient = new LanguageClient(out, logger)
    val messages = BaseProtocolMessage.fromInputStream(in, logger)
    val services = TestUtil.createTestServices(false, logger)
    val lsServer = new LanguageServer(messages, lsClient, services, bspScheduler, logger)
    val runningClientServer = lsServer.startTask.runAsync(bspScheduler)

    val initializeServer = endpoints.Build.initialize.request(
      bsp.InitializeBuildParams(
        "test-bloop-client",
        bloopVersion,
        bspVersion,
        rootUri = bsp.Uri(Environment.cwd.toUri),
        capabilities = bsp.BuildClientCapabilities(List("scala")),
        None
      )
    )

    for {
      // Delay the task to let the bloop server go live
      initializeResult <- initializeServer
      _ = endpoints.Build.initialized.notify(bsp.InitializedBuildParams())
      otherCalls <- runEndpoints(lsClient)
      _ <- endpoints.Build.shutdown.request(bsp.Shutdown())
      _ = endpoints.Build.exit.notify(bsp.Exit())
    } yield {
      closeForcibly(in)
      closeForcibly(out)
      otherCalls match {
        case Right(t) => t
        case Left(error) => throw new MessageOnlyException(s"Unexpectd BSP client error: ${error}")
      }
    }
  }

  case class BspLauncherResult(
      // The status can be optional if server didn't terminate
      status: Option[LauncherStatus],
      launcherLogs: List[String],
      clientLogs: List[String]
  ) {
    def throwIfFailed: Unit = {
      status match {
        case Some(LauncherStatus.SuccessfulRun) => ()
        case unexpected =>
          System.err.println(launcherLogs.mkString(System.lineSeparator))
          Assert.fail(s"Expected 'SuccessfulRun', obtained ${unexpected}! Printing logs:")
      }
    }
  }

  def runBspLauncherWithEnvironment(args: Array[String], shell: Shell): BspLauncherResult = {
    val launcherIn = new PipedInputStream()
    val clientOut = new PipedOutputStream(launcherIn)

    val clientIn = new PipedInputStream()
    val launcherOut = new PipedOutputStream(clientIn)

    var serverRun: Option[LauncherRun] = None
    var serverStatus: Option[LauncherStatus] = None
    val startedServer = Promise[Unit]()
    val startServer = Task {
      setUpLauncher(
        in = launcherIn,
        out = launcherOut,
        startedServer = startedServer,
        shell = shell
      ) { run =>
        serverRun = Some(run)
        serverStatus = Some(run.launcher.cli(args))
      }
    }

    val runServer = startServer.runAsync(bspScheduler)

    val logger = new RecordingLogger()
    val connectToServer = Task.fromFuture(startedServer.future).flatMap { _ =>
      val bspLogger = new BspClientLogger(logger)
      startBspInitializeHandshake(clientIn, clientOut, bspLogger) { c =>
        // Just return, we're only interested in the init handhake + exit
        monix.eval.Task.eval(Right(()))
      }
    }

    def captureLogs: BspLauncherResult = {
      val clientLogs = logger.getMessages().map(kv => s"${kv._1}: ${kv._2}")
      val launcherLogs = serverRun.map(_.logs).getOrElse(Nil)
      BspLauncherResult(serverStatus, launcherLogs, clientLogs)
    }

    try {
      TestUtil.await(FiniteDuration(25, "s"))(connectToServer)
      Await.result(runServer, FiniteDuration(5, "s"))
      captureLogs
    } catch {
      case NonFatal(t) =>
        t.printStackTrace(System.err)
        logger.trace(t)
        captureLogs
    } finally {
      closeForcibly(launcherIn)
      closeForcibly(launcherOut)
    }
  }

  def closeForcibly(c: Closeable): Unit = {
    try c.close()
    catch { case _: Throwable => () }
  }

  def assertLogsContain(
      expected0: List[String],
      total0: List[String],
      prohibited0: List[String] = Nil
  ): Unit = {
    def splitLinesCorrectly(logs: List[String]): List[String] =
      logs.flatMap(_.split(System.lineSeparator()).toList)
    val expected = splitLinesCorrectly(expected0)
    val total = splitLinesCorrectly(total0)
    val missingLogs = expected.filterNot { expectedLog =>
      total.exists(_.contains(expectedLog))
    }

    if (missingLogs.nonEmpty) {
      Assert.fail(
        s"""Missing logs:
           |${missingLogs.map(l => s"-> $l").mkString(System.lineSeparator)}
           |
           |in the actually received logs:
           |
           |${total.map(l => s"> $l").mkString(System.lineSeparator)}
       """.stripMargin
      )
    } else {
      val prohibited = splitLinesCorrectly(prohibited0)
      val prohibitedLogs = prohibited.filter { expectedLog =>
        total.exists(_.contains(expectedLog))
      }

      if (prohibitedLogs.nonEmpty) {
        Assert.fail(
          s"""Prohibited logs:
             |${prohibitedLogs.map(l => s"-> $l").mkString(System.lineSeparator)}
             |
             |appear in the actually received logs:
             |
             |${total.map(l => s"> $l").mkString(System.lineSeparator)}
       """.stripMargin
        )
      }
    }
  }

  def installpyURL(version: String): java.net.URL = {
    new java.net.URL(
      s"https://github.com/scalacenter/bloop/releases/download/v${version}/install.py"
    )
  }

  /**
   * Tests the most critical case of the launcher: bloop is not installed and the launcher
   * installs it in `$HOME/.bloop`. After installing it, it starts up the server, it opens
   * a bsp server session and connects to it, redirecting stdin and stdout appropiately to
   * the server via sockets.
   */
  @Test
  def testBspLauncherWhenUninstalled(): Unit = {
    val result = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)
    val expectedLogs = List(
      Feedback.installingBloop(bloopVersion),
      Feedback.installationLogs(Environment.defaultBloopDirectory),
      Feedback.downloadingInstallerAt(installpyURL(bloopVersion)),
      Feedback.startingBloopServer(Nil),
    )

    val prohibitedLogs = List(
      Feedback.SkippingFullInstallation,
      Feedback.UseFallbackInstallation
    )

    result.throwIfFailed
    assertLogsContain(expectedLogs, result.launcherLogs, prohibitedLogs)
  }

  /**
   * Tests the fallback mechanism when python is not installed: resolves bloop with coursier
   * and runs an embedded bsp server that dies together with the launcher.
   */
  @Test
  def testBspLauncherWhenUninstalledNoPython(): Unit = {
    val result = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithNoPython)
    val expectedLogs = List(
      Feedback.installingBloop(bloopVersion),
      Feedback.SkippingFullInstallation,
      Feedback.UseFallbackInstallation,
      Feedback.resolvingDependency(bloopDependency),
      Feedback.startingBloopServer(Nil),
    )

    val prohibitedLogs = List(
      Feedback.installationLogs(Environment.defaultBloopDirectory),
    )

    result.throwIfFailed
    assertLogsContain(expectedLogs, result.launcherLogs, prohibitedLogs)
  }

  /**
   * Tests the behavior of `--skip-bsp-connection` where the launcher has to
   * install bloop, start a server and return early without establishing a
   * bsp connection.
   *
   * This mode is useful for developer tools wanting to have a way to immediately
   * depend on bloop and start using it without bothering if it's installed or not
   * in a given machine.
   */
  @Test
  def testSkipBspConnectionWithPythonShell(): Unit = {
    val args = Array(bloopVersion, "--skip-bsp-connection")
    setUpLauncher(
      in = System.in,
      out = System.out,
      startedServer = Promise[Unit](),
      shell = shellWithPython
    ) { run =>
      val status = run.launcher.cli(args)
      val expectedLogs = List(
        Feedback.installingBloop(bloopVersion),
        Feedback.installationLogs(Environment.defaultBloopDirectory),
        Feedback.startingBloopServer(Nil),
        Feedback.downloadingInstallerAt(installpyURL(bloopVersion)),
      )

      val prohibitedLogs = List(
        Feedback.SkippingFullInstallation,
        Feedback.UseFallbackInstallation,
        Feedback.resolvingDependency(bloopDependency),
        Feedback.openingBspConnection(Nil),
      )

      Assert.assertEquals(LauncherStatus.SuccessfulRun, status)
      assertLogsContain(expectedLogs, run.logs, prohibitedLogs)
    }
  }

  /**
   * Tests the behavior of `--skip-bsp-connection` when no python is installed:
   * the launcher aborts directly because bloop cannot be installed via the
   * universal method, which means it cannot be used from the client of the
   * launcher.
   */
  @Test
  def testSkipBspConnectionWithNoPythonShell(): Unit = {
    val args = Array(bloopVersion, "--skip-bsp-connection")
    setUpLauncher(
      in = System.in,
      out = System.out,
      startedServer = Promise[Unit](),
      shell = shellWithNoPython
    ) { run =>
      val status = run.launcher.cli(args)
      Assert.assertEquals(LauncherStatus.FailedToInstallBloop, status)
    }
  }

  @After
  def killServerIfRunning(): Unit = {
    val pythonScriptPath = Environment.defaultBloopDirectory.resolve("bloop")

    // If the script exists, then bloop is installed, delete it
    if (Files.exists(pythonScriptPath)) {
      // Kill the server in case it's potentially running in the background
      val script = pythonScriptPath.toAbsolutePath.toString
      // Use ng-stop instead of exit b/c it closes the nailgun server but leaves threads hanging
      val exitCmd = List(script, "--nailgun-port", bloopServerPort.toString, "ng-stop")
      val exitStatus = shellWithPython.runCommand(exitCmd, Some(5))
      if (!exitStatus.isOk) {
        System.err.println(s"${exitCmd.mkString(" ")} produced:")
        if (!exitStatus.output.isEmpty)
          printQuoted(exitStatus.output, System.err)
      }
    }
  }
}
