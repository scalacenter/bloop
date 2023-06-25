package bloop.launcher

import scala.concurrent.Promise

import bloop.bloopgun.util.Environment
import bloop.bloopgun.util.{Feedback => BloopgunFeedback}
import bloop.internal.build.BuildInfo
import bloop.launcher.core.{Feedback => LauncherFeedback}

object LatestStableLauncherSpec extends LauncherSpec("1.4.11")
object LatestMainLauncherSpec extends LauncherSpec(BuildInfo.version)

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
