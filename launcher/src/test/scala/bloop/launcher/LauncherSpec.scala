package bloop.launcher

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import bloop.launcher.core.{AvailableAt, Feedback, Installer, Shell}
import bloop.launcher.util.Environment
import bloop.logging.{BspClientLogger, RecordingLogger}
import bloop.util.TestUtil
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import sbt.internal.util.MessageOnlyException

import scala.concurrent.{Await, Promise}
import scala.concurrent.duration.FiniteDuration
import scala.meta.jsonrpc._
import scala.util.control.NonFatal

// TODO: Make it work with the latest version
// TODO: Replace bloop about by connecting to socket directly
object LauncherSpec extends LauncherBaseSuite("1.2.1", "2.0.0-M1", 9012) {
  // Update the bsp version whenever we change the bloop version
  private final val bloopDependency = s"ch.epfl.scala:bloop-frontend_2.12:${bloopVersion}"
  test("check that environment is correctly mocked") {
    val parentDir = this.bloopBinDirectory.getParent
    assert(parentDir.underlying == Environment.cwd.getParent)
    assert(parentDir.underlying == Environment.homeDirectory.getParent)
  }

  test("fail if arguments are empty") {
    setUpLauncher(shellWithPython) { run =>
      val status = run.launcher.cli(Array())
      assert(status == LauncherStatus.FailedToParseArguments)
      assert(run.logs.exists(_.contains(Feedback.NoBloopVersion)))
    }
  }

  test("check that python is in the classpath") {
    // Python must always be in the classpath in order to run these tests, if this fails install it
    assert(shellWithPython.isPythonInClasspath)
  }

  test("fail for bloop version not supporting launcher") {
    setUpLauncher(shellWithPython) { run =>
      val args = Array("1.0.0")
      val status = run.launcher.cli(args)
      assert(status == LauncherStatus.FailedToInstallBloop)
    }
  }

  test("don't detect installed bloop if any") {
    setUpLauncher(shellWithPython) { setup =>
      // We should not detect the server state unless we have installed it via the launcher
      val status = setup.launcher.detectServerState(bloopVersion)
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
            Feedback.resolvingDependency(bloopDependency)
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
            Feedback.startingBloopServer(Nil)
          )

          // Now, the server should be running, check we can open a connection again
          val result2 = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)
          result2.throwIfFailed
          assertLogsContain(expectedLogs2, result2.launcherLogs, prohibitedLogs2)
        case _ => fail(s"Obtained unexpected ${state}")
      }
    }
  }

  /**
   * Tests the most critical case of the launcher: bloop is not installed and the launcher
   * installs it in `$HOME/.bloop`. After installing it, it starts up the server, it opens
   * a bsp server session and connects to it, redirecting stdin and stdout appropiately to
   * the server via sockets.
   */
  test("install bloop and run it") {
    val result = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithPython)
    val expectedLogs = List(
      Feedback.installingBloop(bloopVersion),
      Feedback.installationLogs(Environment.defaultBloopDirectory),
      Feedback.downloadingInstallerAt(installpyURL(bloopVersion)),
      Feedback.startingBloopServer(Nil)
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
  test("use embedded mode if python is uninstalled") {
    val result = runBspLauncherWithEnvironment(Array(bloopVersion), shellWithNoPython)
    val expectedLogs = List(
      Feedback.installingBloop(bloopVersion),
      Feedback.SkippingFullInstallation,
      Feedback.UseFallbackInstallation,
      Feedback.resolvingDependency(bloopDependency),
      Feedback.startingBloopServer(Nil)
    )

    val prohibitedLogs = List(
      Feedback.installationLogs(Environment.defaultBloopDirectory)
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
  test("install and run bloop, but skip bsp connection") {
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
        Feedback.downloadingInstallerAt(installpyURL(bloopVersion))
      )

      val prohibitedLogs = List(
        Feedback.SkippingFullInstallation,
        Feedback.UseFallbackInstallation,
        Feedback.resolvingDependency(bloopDependency),
        Feedback.openingBspConnection(Nil)
      )

      assert(LauncherStatus.SuccessfulRun == status)
      assertLogsContain(expectedLogs, run.logs, prohibitedLogs)
    }
  }

  /**
   * Tests the behavior of `--skip-bsp-connection` when no python is installed:
   * the launcher aborts directly because bloop cannot be installed via the
   * universal method, which means it cannot be used from the client of the
   * launcher.
   */
  test("fail if skip bsp connection is set and bloop can only run in embedded mode") {
    val args = Array(bloopVersion, "--skip-bsp-connection")
    setUpLauncher(
      in = System.in,
      out = System.out,
      startedServer = Promise[Unit](),
      shell = shellWithNoPython
    ) { run =>
      val status = run.launcher.cli(args)
      assert(LauncherStatus.FailedToInstallBloop == status)
    }
  }
}
