package bloop.launcher

import bloop.io.Paths
import bloop.io.AbsolutePath
import bloop.io.Environment.LineSplitter
import bloop.testing.BaseSuite
import bloop.bloopgun.core.Shell
import bloop.bloopgun.util.Environment
import bloop.internal.build.BuildTestInfo

import java.nio.file.Files
import java.nio.file.Path
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.{util => ju}

import scala.collection.JavaConverters._
import scala.concurrent.Promise

import bloop.bloopgun.BloopgunCli
import bloop.bloopgun.ServerConfig
import bloop.bloopgun.core.ServerStatus
import snailgun.logging.SnailgunLogger
import bloop.TestSchedulers

/**
 * Defines a base suite to test the launcher. The test suite hijacks system
 * properties and environment variables so that every test case has an isolated
 * environment. The environment is then restored after every test case and
 * after all tests have run.
 */
abstract class LauncherBaseSuite(
    val bloopVersion: String,
    val bspVersion: String
) extends BaseSuite {

  protected val shellWithPython = new Shell(true, true)

  def stopServer(
      bloopServerPortOrDaemonDir: Either[Int, Path],
      complainIfError: Boolean
  ): Unit = {
    val dummyIn = new ByteArrayInputStream(new Array(0))
    val out = new ByteArrayOutputStream()
    val ps = new PrintStream(out)
    val bloopgunShell = bloop.bloopgun.core.Shell.default
    val cli = new BloopgunCli(bloopVersion, dummyIn, ps, ps, bloopgunShell)

    // Use ng-stop instead of exit b/c it closes the nailgun server but leaves threads hanging
    val exitCmd = {
      val ngArgs = bloopServerPortOrDaemonDir match {
        case Left(port) =>
          List("--nailgun-port", port.toString)
        case Right(path) =>
          List("--daemon-dir", path.toString)
      }
      ngArgs ::: List("exit")
    }
    val code = cli.run(exitCmd.toArray)

    if (code != 0 && complainIfError) {
      val output = out.toByteArray()
      if (!output.isEmpty)
        printQuoted(new String(out.toByteArray(), StandardCharsets.UTF_8), System.err)
    }
  }

  import java.{util => ju}

  case class LauncherRun(launcher: LauncherMain, output: ByteArrayOutputStream) {
    def logs: List[String] =
      (new String(output.toByteArray, StandardCharsets.UTF_8)).splitLines.toList
  }

  def setUpLauncher(
      shell: Shell,
      bloopServerPortOrDaemonDir: Either[Int, Path],
      startedServer: Promise[Unit] = Promise[Unit]()
  )(
      launcherLogic: LauncherRun => Unit
  ): Unit = {
    import java.io.ByteArrayInputStream
    val clientIn = new ByteArrayInputStream(new Array[Byte](0))
    val clientOut = new ByteArrayOutputStream()
    setUpLauncher(clientIn, clientOut, shell, startedServer, bloopServerPortOrDaemonDir)(
      launcherLogic
    )
  }

  def setUpLauncher[T](
      in: InputStream,
      out: OutputStream,
      shell: Shell,
      startedServer: Promise[Unit],
      bloopServerPortOrDaemonDir: Either[Int, Path]
  )(
      launcherLogic: LauncherRun => T
  ): T = {
    import java.io.ByteArrayOutputStream
    import java.io.PrintStream

    val listenOn = bloopServerPortOrDaemonDir.left
      .map(port => (None, Some(port)))
      .map(Some(_))
    val defaultConfig = ServerConfig(listenOn = listenOn)

    val baos = new ByteArrayOutputStream()
    val tee = new util.TeeOutputStream(baos, System.err)
    val ps = new PrintStream(tee, true, "UTF-8")
    val launcher = new LauncherMain(
      in,
      out,
      ps,
      StandardCharsets.UTF_8,
      shell,
      listenOn,
      startedServer
    )
    val run = new LauncherRun(launcher, baos)

    import monix.execution.misc.NonFatal
    try launcherLogic(run)
    catch {
      case NonFatal(t) =>
        println("Test case failed with the following logs: ", System.err)
        printQuoted(run.logs.mkString(lineSeparator), System.err)
        throw t
    } finally {
      if (ps != null) ps.close()
      stopServer(bloopServerPortOrDaemonDir, complainIfError = true)
    }
  }

  // We constrain # of threads to guarantee no hanging threads/resources
  import monix.execution.Scheduler
  import monix.execution.ExecutionModel
  private val bspScheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors
      .newFixedThreadPool(4, TestSchedulers.threadFactory("bspScheduler")),
    ExecutionModel.AlwaysAsyncExecution
  )

  import bloop.logging.BspClientLogger
  import monix.eval.Task
  import scala.meta.jsonrpc.BaseProtocolMessage
  import bloop.util.TestUtil
  import scala.meta.jsonrpc.Response
  import bloop.bsp.BloopLanguageClient
  private def startBspInitializeHandshake[T](
      in: InputStream,
      out: OutputStream,
      logger: BspClientLogger[_]
  )(runEndpoints: BloopLanguageClient => Task[Either[Response.Error, T]]): Task[T] = {
    import ch.epfl.scala.bsp
    import ch.epfl.scala.bsp.endpoints
    import bloop.bsp.BloopLanguageClient
    import bloop.bsp.BloopLanguageServer
    implicit val lsClient = new BloopLanguageClient(out, logger)
    val messages = BaseProtocolMessage.fromInputStream(in, logger)
    val services = TestUtil.createTestServices(false, logger)
    val lsServer = new BloopLanguageServer(messages, lsClient, services, bspScheduler, logger)
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
      import sbt.internal.util.MessageOnlyException
      otherCalls match {
        case Right(t) => t
        case Left(error) => throw new MessageOnlyException(s"Unexpected BSP client error: ${error}")
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
          System.err.println(launcherLogs.mkString(lineSeparator))
          fail(s"Expected 'SuccessfulRun', obtained ${unexpected}!")
      }
    }
  }

  def runBspLauncherWithEnvironment(
      args: Array[String],
      shell: Shell,
      bloopServerPortOrDaemonDir: Either[Int, Path]
  ): BspLauncherResult = {
    import java.io.PipedInputStream
    import java.io.PipedOutputStream
    import bloop.logging.RecordingLogger
    val launcherIn = new PipedInputStream()
    val clientOut = new PipedOutputStream(launcherIn)

    val clientIn = new PipedInputStream()
    val launcherOut = new PipedOutputStream(clientIn)

    var serverRun: Option[LauncherRun] = None
    var serverStatus: Option[LauncherStatus] = None
    val startedServer = Promise[Unit]()
    setUpLauncher(
      in = launcherIn,
      out = launcherOut,
      shell = shell,
      startedServer = startedServer,
      bloopServerPortOrDaemonDir = bloopServerPortOrDaemonDir
    ) { run =>
      serverRun = Some(run)
      val l = new ju.concurrent.CountDownLatch(1)
      val serverStatusTask = Task {
        serverStatus = Some(run.launcher.cli(args))
        l.countDown()
      }
      serverStatusTask.runAsync(bspScheduler)

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

      import scala.util.control.NonFatal
      try {
        import scala.concurrent.Await
        import scala.concurrent.duration.FiniteDuration
        // Test can be slow in Windows...
        TestUtil.await(FiniteDuration(40, "s"))(connectToServer)
        l.await()
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
  }

  import java.io.Closeable
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
      logs.flatMap(_.splitLines.toList)
    val expected = splitLinesCorrectly(expected0)
    val total = splitLinesCorrectly(total0)
    val missingLogs = expected.filterNot { expectedLog =>
      total.exists(_.contains(expectedLog))
    }

    if (missingLogs.nonEmpty) {
      fail(
        s"""Missing logs:
           |${missingLogs.map(l => s"-> $l").mkString(lineSeparator)}
           |
           |in the actually received logs:
           |
           |${total.map(l => s"> $l").mkString(lineSeparator)}
         """.stripMargin
      )
    } else {
      val prohibited = splitLinesCorrectly(prohibited0)
      val prohibitedLogs = prohibited.filter { expectedLog =>
        total.exists(_.contains(expectedLog))
      }

      if (prohibitedLogs.nonEmpty) {
        fail(
          s"""Prohibited logs:
             |${prohibitedLogs.map(l => s"-> $l").mkString(lineSeparator)}
             |
             |appear in the actually received logs:
             |
             |${total.map(l => s"> $l").mkString(lineSeparator)}
           """.stripMargin
        )
      }
    }
  }
}
