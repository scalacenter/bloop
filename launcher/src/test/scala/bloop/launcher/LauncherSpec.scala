package bloop.launcher

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

import bloop.launcher.LauncherStatus.SuccessfulRun
import bloop.launcher.core.{AvailableAt, Feedback, Installer, Shell}
import bloop.logging.{BspClientLogger, DebugFilter, RecordingLogger}
import bloop.tasks.TestUtil
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import org.junit.{After, Assert, Test}
import sbt.internal.util.MessageOnlyException

import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc._
import scala.util.control.NonFatal

class LauncherSpec extends AbstractLauncherSpec {
  // Update the bsp version whenever we change the bloop version
  private final val bspVersion = "2.0.0-M1"
  private final val bloopVersion = "1.1.2"
  private final val bloopServerPort = 9012
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
    } finally {
      if (ps != null) ps.close()
    }
  }

  @Test
  def testSystemPropertiesMockingWork(): Unit = {
    // Test from https://stefanbirkner.github.io/system-rules/index.html
    val parentDir = this.binDirectory.getParent
    parentDir.toFile.deleteOnExit()
    Assert.assertEquals(parentDir, Paths.get(System.getProperty("user.dir")).getParent)
    Assert.assertEquals(parentDir, Paths.get(System.getProperty("user.home")).getParent)
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
    val run = setUpLauncher(System.in, System.out, shellWithPython) { run =>
      // Install the launcher via `install.py`, which is the preferred installation method
      val launcher = run.launcher
      val state = Installer.installBloopBinaryInHomeDir(
        this.binDirectory,
        launcher.defaultBloopDirectory,
        bloopVersion,
        launcher.out,
        launcher.detectServerState(_),
        launcher.shell
      )

      // We should detect the bloop binary in the place where we installed it!
      val bloopDir = launcher.defaultBloopDirectory.resolve("bloop")
      state match {
        case Some(AvailableAt(binary)) if binary.head == bloopDir.toString =>
          // After installing, let's run the launcher in an environment where bloop is available
          runBspLauncherWithEnvironment(shellWithPython)
          // Now, the server should be running, check we can open a connection again
          runBspLauncherWithEnvironment(shellWithPython)
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
    implicit val ctx: DebugFilter = DebugFilter.Bsp
    import ch.epfl.scala.bsp
    import ch.epfl.scala.bsp.endpoints
    def createServices(addDiagnosticsHandler: Boolean, logger0: BspClientLogger[_]): Services = {
      val logger: bloop.logging.Logger = logger0
      val rawServices = Services
        .empty(logger0)
        .notification(endpoints.Build.showMessage) {
          case bsp.ShowMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
          case bsp.ShowMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
          case bsp.ShowMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
          case bsp.ShowMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
        }
        .notification(endpoints.Build.logMessage) {
          case bsp.LogMessageParams(bsp.MessageType.Log, _, _, msg) => logger.debug(msg)
          case bsp.LogMessageParams(bsp.MessageType.Info, _, _, msg) => logger.info(msg)
          case bsp.LogMessageParams(bsp.MessageType.Warning, _, _, msg) => logger.warn(msg)
          case bsp.LogMessageParams(bsp.MessageType.Error, _, _, msg) => logger.error(msg)
        }

      // Lsp4s fails if we try to repeat a handler for a given notification
      if (!addDiagnosticsHandler) rawServices
      else {
        rawServices.notification(endpoints.Build.publishDiagnostics) {
          case bsp.PublishDiagnosticsParams(uri, _, _, diagnostics, _) =>
            // We prepend diagnostics so that tests can check they came from this notification
            def printDiagnostic(d: bsp.Diagnostic): String = s"[diagnostic] ${d.message} ${d.range}"
            diagnostics.foreach { d =>
              d.severity match {
                case Some(bsp.DiagnosticSeverity.Error) => logger.error(printDiagnostic(d))
                case Some(bsp.DiagnosticSeverity.Warning) => logger.warn(printDiagnostic(d))
                case Some(bsp.DiagnosticSeverity.Information) => logger.info(printDiagnostic(d))
                case Some(bsp.DiagnosticSeverity.Hint) => logger.debug(printDiagnostic(d))
                case None => logger.info(printDiagnostic(d))
              }
            }
        }
      }
    }

    implicit val lsClient = new LanguageClient(out, logger)
    val messages = BaseProtocolMessage.fromInputStream(in, logger)
    val services = createServices(false, logger)
    val lsServer = new LanguageServer(messages, lsClient, services, bspScheduler, logger)
    val runningClientServer = lsServer.startTask.runAsync(bspScheduler)

    val cwd = Paths.get(System.getProperty("user.dir"))
    val initializeServer = endpoints.Build.initialize.request(
      bsp.InitializeBuildParams(
        "test-bloop-client",
        bloopVersion,
        bspVersion,
        rootUri = bsp.Uri(cwd.toUri),
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

  case class BspSessionLogs(launcher: List[String], client: List[String])
  def runBspLauncherWithEnvironment(shell: Shell): BspSessionLogs = {
    val launcherIn = new PipedInputStream()
    val clientOut = new PipedOutputStream(launcherIn)

    val clientIn = new PipedInputStream()
    val launcherOut = new PipedOutputStream(clientIn)

    var serverRun: Option[LauncherRun] = None
    val startedServer = Promise[Unit]()
    val startServer = Task {
      setUpLauncher(
        in = launcherIn,
        out = launcherOut,
        startedServer = startedServer,
        shell = shell
      ) { run =>
        serverRun = Some(run)
        val status = run.launcher.cli(Array("1.1.2"))
        if (status != SuccessfulRun) {
          // Print if exit code is not successsful
          System.err.println(run.logs.mkString("\n"))
        }
      }
    }

    startServer.runAsync(bspScheduler)

    val logger = new RecordingLogger()
    val connectToServer = Task.fromFuture(startedServer.future).flatMap { _ =>
      val bspLogger = new BspClientLogger(logger)
      startBspInitializeHandshake(clientIn, clientOut, bspLogger) { c =>
        // Just return, we're only interested in the init handhake + exit
        monix.eval.Task.eval(Right(()))
      }
    }

    def captureLogs: BspSessionLogs = {
      val clientLogs = logger.getMessages().map(kv => s"${kv._1}: ${kv._2}")
      val launcherLogs = serverRun.map(_.logs).getOrElse(Nil)
      BspSessionLogs(launcherLogs, clientLogs)
    }

    try {
      TestUtil.await(FiniteDuration(25, "s"))(connectToServer)
      captureLogs
    } catch {
      case NonFatal(t) =>
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

  /**
   * Tests the most critical case of the launcher: bloop is not installed and the launcher
   * installs it in `$HOME/.bloop`. After installing it, it starts up the server, it opens
   * a bsp server session and connects to it, redirecting stdin and stdout appropiately to
   * the server via sockets.
   */
  @Test
  def testBspLauncherWhenUninstalled(): Unit = {
    runBspLauncherWithEnvironment(shellWithPython)
  }

  /**
   * Tests the fallback mechanism when python is not installed: resolves bloop with coursier
   * and runs an embedded bsp server that dies together with the launcher.
   */
  @Test
  def testBspLauncherWhenUninstalledNoPython(): Unit = {
    val logs = runBspLauncherWithEnvironment(shellWithNoPython)
    System.err.println(logs.launcher.mkString("\n"))
  }

  @After
  def killServerIfRunning(): Unit = {
    val pythonScriptPath =
      Paths.get(System.getProperty("user.home")).resolve(".bloop").resolve("bloop")

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
