package bloop.launcher

import java.io._
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import bloop.internal.build.BuildInfo
import bloop.util.TestUtil
import bloop.bloopgun.ServerConfig
import bloop.launcher.core.{Feedback => LauncherFeedback}
import bloop.bloopgun.util.Environment
import bloop.bloopgun.util.{Feedback => BloopgunFeedback}
import bloop.bloopgun.core.AvailableAtPath
import java.util.concurrent.atomic.AtomicInteger
import java.security.SecureRandom
import java.nio.file.attribute.PosixFilePermissions
import java.net.ServerSocket
import scala.concurrent.Promise

object LatestMasterLauncherSpec
    extends LauncherSpec(BuildInfo.version, () => Left(LauncherSpecHelper.openPort()))

object LauncherSpecHelper {
  val count = new AtomicInteger
  val rng = math.abs(new SecureRandom().nextInt().toLong)

  def openPort(): Int = {
    val s = new ServerSocket(0)
    val port = s.getLocalPort()
    s.close()
    port
  }
}

object LatestMasterLauncherDomainSocketSpec
    extends LauncherSpec(
      BuildInfo.version,
      () => {
        import LauncherSpecHelper.{count, rng}
        val count0 = count.incrementAndGet()
        val dir = TestUtil.tmpDir(strictPermissions = true).resolve(count0.toString)
        Files.createDirectories(dir)
        if (!scala.util.Properties.isWin) {
          Files.setPosixFilePermissions(
            dir,
            PosixFilePermissions.fromString("rwx------")
          )
        }
        Right(dir)
      }
    )

class LauncherSpec(
    bloopVersion: String,
    bloopServerPortOrDaemonDir: () => Either[Int, Path]
) extends LauncherBaseSuite(bloopVersion, BuildInfo.bspVersion) {

  // Copied from elsewhere here
  private object Num {
    def unapply(s: String): Option[Int] =
      if (s.nonEmpty && s.forall(_.isDigit)) Some(s.toInt)
      else None
  }
  private def bloopOrg(version: String): String =
    version.split("[-.]") match {
      case Array(Num(maj), Num(min), Num(patch), _*) =>
        import scala.math.Ordering.Implicits._
        if (Seq(maj, min, patch) >= Seq(1, 4, 11) && version != "1.4.11")
          "io.github.alexarchambault.bleep"
        else "ch.epfl.scala"
      case _ => "ch.epfl.scala"
    }

  private final val bloopDependency =
    s"${bloopOrg(bloopVersion)}:bloop-frontend_2.12:${bloopVersion}"
  test("fail if arguments are empty") {
    setUpLauncher(shellWithPython, bloopServerPortOrDaemonDir()) { run =>
      val status = run.launcher.cli(Array())
      assert(status == LauncherStatus.FailedToParseArguments)
      assert(run.logs.exists(_.contains(LauncherFeedback.NoBloopVersion)))
    }
  }

  ignore("fail for bloop version not supporting launcher") {
    setUpLauncher(shellWithPython, bloopServerPortOrDaemonDir()) { run =>
      val args = Array("1.0.0")
      val status = run.launcher.cli(args)
      assert(status == LauncherStatus.FailedToInstallBloop)
    }
  }

  test("when bloop is uninstalled, resolve bloop, start and connect to server via BSP") {
    val bloopServerPortOrDaemonDir0 = bloopServerPortOrDaemonDir()
    val listenOn = bloopServerPortOrDaemonDir0.left
      .map(port => (None, Some(port)))
      .map(Some(_))
    val defaultConfig = ServerConfig(listenOn = listenOn)
    val result = runBspLauncherWithEnvironment(
      Array(bloopVersion),
      shellWithPython,
      bloopServerPortOrDaemonDir0
    )
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

    val bloopServerPortOrDaemonDir0 = bloopServerPortOrDaemonDir()
    val listenOn = bloopServerPortOrDaemonDir0.left
      .map(port => (None, Some(port)))
      .map(Some(_))
    val defaultConfig = ServerConfig(listenOn = listenOn)

    setUpLauncher(
      in = System.in,
      out = System.out,
      startedServer = Promise[Unit](),
      shell = shellWithPython,
      bloopServerPortOrDaemonDir = bloopServerPortOrDaemonDir0
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
