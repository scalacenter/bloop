package bloop.launcher

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.launcher.core.Installer
import bloop.internal.build.BuildInfo
import bloop.bloopgun.util.Environment
import bloop.logging.{BspClientLogger, RecordingLogger}
import bloop.util.TestUtil
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import sbt.internal.util.MessageOnlyException

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc._
import scala.util.control.NonFatal
import bloop.bloopgun.ServerConfig

import bloop.launcher.core.{Feedback => LauncherFeedback}
import bloop.bloopgun.util.{Feedback => BloopgunFeedback}
import bloop.bloopgun.core.AvailableAtPath

object LatestStableLauncherSpec extends LauncherSpec("1.3.2")
object LatestMasterLauncherSpec extends LauncherSpec(BuildInfo.version)

class LauncherSpec(bloopVersion: String)
    extends LauncherBaseSuite(bloopVersion, BuildInfo.bspVersion, 9014) {
  private final val bloopDependency = s"ch.epfl.scala:bloop-frontend_2.12:${bloopVersion}"
  test("fail if arguments are empty") {
    setUpLauncher(shellWithPython) { run =>
      val status = run.launcher.cli(Array())
      assert(status == LauncherStatus.FailedToParseArguments)
      assert(run.logs.exists(_.contains(LauncherFeedback.NoBloopVersion)))
    }
  }

  test("check that environment is correctly mocked") {
    val parentDir = this.bloopBinDirectory.getParent
    assert(parentDir.underlying == Environment.cwd.getParent)
    assert(parentDir.underlying == Environment.homeDirectory.getParent)
  }

  test("check that python is in the classpath") {
    // Python must always be in the classpath in order to run these tests, if this fails install it
    assert(shellWithPython.isPythonInClasspath)
  }

  ignore("fail for bloop version not supporting launcher") {
    setUpLauncher(shellWithPython) { run =>
      val args = Array("1.0.0")
      val status = run.launcher.cli(args)
      assert(status == LauncherStatus.FailedToInstallBloop)
    }
  }

  test("don't detect installed bloop if there's one installed in the machine running launcher") {
    setUpLauncher(shellWithPython) { setup =>
      // We should not detect the server state unless we have installed it via the launcher
      val status = detectServerState(bloopVersion, setup.launcher.shell)
      assert(None == status)
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
  test("run bsp server when bloop is installed but not running") {
    setUpLauncher(shellWithPython) { run =>
      // Install the launcher via `install.py`, which is the preferred installation method
      val launcher = run.launcher
      val state = Installer.installBloopBinaryInHomeDir(
        bloopBinDirectory.underlying,
        Environment.defaultBloopDirectory,
        bloopVersion,
        launcher.out,
        detectServerState(_, launcher.shell),
        launcher.shell,
        bloopInstallerURL
      )

      run.output.reset()

      // We should detect the bloop binary in the place where we installed it!
      val bloopDir = Environment.defaultBloopDirectory.toAbsolutePath().toRealPath()
      state match {
        case Some(AvailableAtPath(path))
            if path.toAbsolutePath().toRealPath().getParent == bloopDir =>
          // After installing, let's run the launcher in an environment where bloop is available
          val result1 = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)
          val expectedLogs1 = List(
            BloopgunFeedback.DetectedBloopInstallation,
            BloopgunFeedback.startingBloopServer(defaultConfig),
            LauncherFeedback.openingBspConnection(Nil)
          )

          val prohibitedLogs1 = List(
            LauncherFeedback.installingBloop(bloopVersion),
            BloopgunFeedback.resolvingDependency(bloopDependency)
          )

          result1.throwIfFailed
          assertLogsContain(expectedLogs1, result1.launcherLogs, prohibitedLogs1)

          val expectedLogs2 = List(
            LauncherFeedback.openingBspConnection(Nil)
          )

          val prohibitedLogs2 = List(
            BloopgunFeedback.DetectedBloopInstallation,
            LauncherFeedback.installingBloop(bloopVersion),
            BloopgunFeedback.resolvingDependency(bloopDependency),
            BloopgunFeedback.startingBloopServer(defaultConfig)
          )

          // Now, the server should be running, check we can open a connection again
          val result2 = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)
          result2.throwIfFailed
          assertLogsContain(expectedLogs2, result2.launcherLogs, prohibitedLogs2)
        case _ => fail(s"Obtained unexpected ${state}")
      }
    }
  }

  test("when bloop is uninstalled, resolve bloop, start and connect to server via BSP") {
    val result = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)
    val expectedLogs = List(
      BloopgunFeedback.resolvingDependency(bloopDependency),
      BloopgunFeedback.startingBloopServer(defaultConfig),
      LauncherFeedback.openingBspConnection(Nil)
    )

    result.throwIfFailed
    assertLogsContain(expectedLogs, result.launcherLogs, Nil)
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
  test(
    "when bloop is uninstalled and `--skip-bsp-connection`, resolve bloop, start and connect to server via BSP"
  ) {
    val args = Array(bloopVersion, "--skip-bsp-connection")
    setUpLauncher(
      in = System.in,
      out = System.out,
      startedServer = Promise[Unit](),
      shell = shellWithPython
    ) { run =>
      val status = run.launcher.cli(args)
      val expectedLogs = List(
        BloopgunFeedback.resolvingDependency(bloopDependency),
        BloopgunFeedback.startingBloopServer(defaultConfig)
      )

      val prohibitedLogs = List(
        LauncherFeedback.openingBspConnection(Nil)
      )

      assert(LauncherStatus.SuccessfulRun == status)
      assertLogsContain(expectedLogs, run.logs, prohibitedLogs)
    }
  }
}
