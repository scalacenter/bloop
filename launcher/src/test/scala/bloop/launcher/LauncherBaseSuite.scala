package bloop.launcher

import bloop.io.Paths
import bloop.io.AbsolutePath
import bloop.testing.BaseSuite
import bloop.launcher.core.Shell
import bloop.launcher.util.Environment
import bloop.internal.build.BuildTestInfo

import java.nio.file.Files
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream

import scala.concurrent.Promise
import scala.collection.JavaConverters._

import coursier.CoursierPaths

/**
 * Defines a base suite to test the launcher. The test suite hijacks system
 * properties and environment variables so that every test case has an isolated
 * environment. The environment is then restored after every test case and
 * after all tests have run.
 */
abstract class LauncherBaseSuite(
    val bloopVersion: String,
    val bspVersion: String,
    val bloopServerPort: Int
) extends BaseSuite {
  val oldEnv = System.getenv()
  val oldCwd = AbsolutePath(System.getProperty("user.dir"))
  val oldHomeDir = AbsolutePath(System.getProperty("user.home"))
  val oldIvyHome = Option(System.getProperty("ivy.home"))
  val oldCoursierCacheDir = Option(System.getProperty("coursier.cache"))
  val ivyHome = oldHomeDir.resolve(".ivy2")
  val coursierCacheDir = AbsolutePath(CoursierPaths.cacheDirectory())
  val bloopBinDirectory = AbsolutePath(Files.createTempDirectory("bsp-bin"))

  protected val shellWithPython = new Shell(true, true)
  protected val shellWithNoPython = new Shell(true, false)
  protected val bloopInstallerURL = installpyURL(bloopVersion)

  // Init code acting as beforeAll()
  stopServer(complainIfError = false)
  prependToPath(bloopBinDirectory.syntax)
  System.setProperty("ivy.home", ivyHome.syntax)
  System.setProperty("bloop.home", AbsolutePath(BuildTestInfo.baseDirectory).syntax)
  System.setProperty("coursier.cache", coursierCacheDir.syntax)
  val hijackedBloop = bloopBinDirectory.resolve("bloop")
  writeFile(hijackedBloop, "I am not a script and I must fail to be executed")
  hijackedBloop.toFile.setExecutable(true)
  assertIsFile(hijackedBloop)

  private def isStable(bloopVersion: String): Boolean = !bloopVersion.contains("-")
  private def shouldSkipTestSuite(bloopVersion: String): Boolean = {
    import java.io.File
    import java.io.IOException
    try {
      val f = new File(bloopInstallerURL.toURI())
      val witnessInstalledFile = new File(f.getParentFile(), "installed.txt")
      if (!witnessInstalledFile.exists()) true
      else {
        val contents = new String(
          Files.readAllBytes(witnessInstalledFile.toPath),
          StandardCharsets.UTF_8
        )

        !contents.contains(bloopVersion)
      }
    } catch {
      // URL is not a file, so it's GitHub hosted for a stable version
      case _: IllegalArgumentException => false
    }
  }

  val skipTestSuite = shouldSkipTestSuite(bloopVersion)
  override def test(name: String)(fun: => Any): Unit = {
    if (skipTestSuite) {
      super.ignore(name)(fun)
    } else {
      val newCwd = AbsolutePath(Files.createTempDirectory("cwd-test"))
      val newHome = AbsolutePath(Files.createTempDirectory("home-test"))

      val newFun = () => {
        try {
          stopServer(complainIfError = false)
          System.setProperty("user.dir", newCwd.syntax)
          System.setProperty("user.home", newHome.syntax)
          fun
        } finally {
          stopServer(complainIfError = true)
          System.setProperty("user.dir", oldCwd.syntax)
          System.setProperty("user.home", oldHomeDir.syntax)
          Paths.delete(newCwd)
          Paths.delete(newHome)
          ()
        }
      }

      super.test(name)(newFun())
    }
  }

  private def stopServer(complainIfError: Boolean): Unit = {
    val bloopPath = Environment.defaultBloopDirectory.resolve("bloop")
    if (Files.exists(bloopPath)) {
      // Kill the server in case it's potentially running in the background
      val script = bloopPath.toAbsolutePath.toString
      // Use ng-stop instead of exit b/c it closes the nailgun server but leaves threads hanging
      val exitCmd = List(script, "--nailgun-port", bloopServerPort.toString, "ng-stop")
      val exitStatus = shellWithPython.runCommand(exitCmd, Some(5))
      if (!exitStatus.isOk && complainIfError) {
        System.err.println(s"${exitCmd.mkString(" ")} produced:")
        if (!exitStatus.output.isEmpty)
          printQuoted(exitStatus.output, System.err)
      }
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    oldIvyHome.foreach(h => System.setProperty("ivy.home", h))
    oldCoursierCacheDir.foreach(c => System.setProperty("coursier.cache", c))
    val newOldMap = oldEnv.asScala.toMap.asJava
    changeEnvironment(newOldMap)
    Paths.delete(bloopBinDirectory)
  }

  import java.{util => ju}
  private def prependToPath(newEntry: String): Unit = {
    import java.io.File
    import bloop.util.CrossPlatform
    val pathVariableName = if (CrossPlatform.isWindows) "Path" else "PATH"
    val ourEnv = System.getenv().asScala.toMap
    val currentPath = ourEnv
      .get(pathVariableName)
      .getOrElse(sys.error(s"No $pathVariableName in env!"))
    val newPath = newEntry + File.pathSeparator + currentPath
    changeEnvironment((ourEnv + (pathVariableName -> newPath)).asJava)
  }

  private def changeEnvironment(newEnv: ju.Map[String, String]): Unit = {
    // From https://stackoverflow.com/questions/318239/how-do-i-set-environment-variables-from-java
    try {
      val envClass = Class.forName("java.lang.ProcessEnvironment")
      val envField = envClass.getDeclaredField("theEnvironment")
      envField.setAccessible(true)
      val currentEnv = envField.get(null).asInstanceOf[ju.Map[String, String]]
      currentEnv.putAll(newEnv)
      val caseInsensitiveEnvField =
        envClass.getDeclaredField("theCaseInsensitiveEnvironment")
      caseInsensitiveEnvField.setAccessible(true)
      val currentCaseInsitiveEnv =
        caseInsensitiveEnvField.get(null).asInstanceOf[ju.Map[String, String]]
      currentCaseInsitiveEnv.putAll(newEnv)
    } catch {
      case _: NoSuchFieldException =>
        val classes = classOf[ju.Collections].getDeclaredClasses()
        val currentEnv = System.getenv()
        classes.foreach { currentClass =>
          if (currentClass.getName() == "java.util.Collections$UnmodifiableMap") {
            val mField = currentClass.getDeclaredField("m")
            mField.setAccessible(true)
            val currentEnvMap = mField.get(currentEnv).asInstanceOf[ju.Map[String, String]]
            currentEnvMap.clear()
            currentEnvMap.putAll(newEnv)
          }
        }
    }
  }

  case class LauncherRun(launcher: LauncherMain, output: ByteArrayOutputStream) {
    def logs: List[String] =
      (new String(output.toByteArray, StandardCharsets.UTF_8)).split(System.lineSeparator()).toList
  }

  def setUpLauncher(shell: Shell, startedServer: Promise[Unit] = Promise[Unit]())(
      launcherLogic: LauncherRun => Unit
  ): Unit = {
    import java.io.ByteArrayInputStream
    val clientIn = new ByteArrayInputStream(new Array[Byte](0))
    val clientOut = new ByteArrayOutputStream()
    setUpLauncher(clientIn, clientOut, shell, startedServer)(launcherLogic)
  }

  def setUpLauncher(
      in: InputStream,
      out: OutputStream,
      shell: Shell,
      startedServer: Promise[Unit]
  )(
      launcherLogic: LauncherRun => Unit
  ): Unit = {
    import java.io.ByteArrayOutputStream
    import java.io.PrintStream
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos, true, "UTF-8")
    val port = Some(bloopServerPort)
    val launcher = new LauncherMain(
      in,
      out,
      ps,
      StandardCharsets.UTF_8,
      shell,
      port,
      startedServer,
      _ => bloopInstallerURL
    )
    val run = new LauncherRun(launcher, baos)

    import monix.execution.misc.NonFatal
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

  // We constrain # of threads to guarantee no hanging threads/resources
  import monix.execution.Scheduler
  import monix.execution.ExecutionModel
  private val bspScheduler: Scheduler = Scheduler(
    java.util.concurrent.Executors.newFixedThreadPool(4),
    ExecutionModel.AlwaysAsyncExecution
  )

  import bloop.logging.BspClientLogger
  import scala.meta.jsonrpc.LanguageClient
  import monix.eval.Task
  import scala.meta.jsonrpc.BaseProtocolMessage
  import bloop.util.TestUtil
  import scala.meta.jsonrpc.LanguageServer
  import scala.meta.jsonrpc.Response
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
          System.err.println(launcherLogs.mkString(System.lineSeparator))
          fail(s"Expected 'SuccessfulRun', obtained ${unexpected}! Printing logs:")
      }
    }
  }

  def runBspLauncherWithEnvironment(args: Array[String], shell: Shell): BspLauncherResult = {
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

    import scala.util.control.NonFatal
    try {
      import scala.concurrent.Await
      import scala.concurrent.duration.FiniteDuration
      TestUtil.await(FiniteDuration(30, "s"))(connectToServer)
      Await.result(runServer, FiniteDuration(10, "s"))
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
      logs.flatMap(_.split(System.lineSeparator()).toList)
    val expected = splitLinesCorrectly(expected0)
    val total = splitLinesCorrectly(total0)
    val missingLogs = expected.filterNot { expectedLog =>
      total.exists(_.contains(expectedLog))
    }

    if (missingLogs.nonEmpty) {
      fail(
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
        fail(
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
    // Assumes non-stable releases will always have a dash inside its version
    if (isStable(version)) {
      // Use GitHub-hosted installation script for stable releases
      new java.net.URL(
        s"https://github.com/scalacenter/bloop/releases/download/v${version}/install.py"
      )
    } else {
      BuildTestInfo.versionedInstallScript.toPath.toUri().toURL()
    }
  }
}
