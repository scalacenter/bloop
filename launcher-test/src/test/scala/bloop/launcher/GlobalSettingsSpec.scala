package bloop.launcher

import java.nio.file.Files
import scala.concurrent.Promise
import bloop.bloopgun.util.Environment
import bloop.internal.build.BuildInfo

object GlobalSettingsSpec extends LauncherBaseSuite(BuildInfo.version, BuildInfo.bspVersion, 9014) {
  val launcherArguments = Array(bloopVersion, "--skip-bsp-connection")

  def writeJsonSettings(json: String): Unit = {
    val settings = Environment.bloopGlobalSettingsPath
    Files.createDirectories(settings.getParent())
    Files.write(settings, json.getBytes())
    ()
  }

  def setupLauncherWithGlobalJsonSettings(
      json: String
  )(fn: (LauncherRun, LauncherStatus) => Unit): Unit = {
    writeJsonSettings(json)
    setUpLauncher(
      in = System.in,
      out = System.out,
      startedServer = Promise[Unit](),
      shell = shellWithPython
    ) { run =>
      val status = run.launcher.cli(launcherArguments)
      fn(run, status)
    }
  }

  def runBspLauncherWithGlobalJsonSettings(json: String)(fn: BspLauncherResult => Unit): Unit = {
    writeJsonSettings(json)
    val result = runBspLauncherWithEnvironment(launcherArguments, shellWithPython)
    fn(result)
  }

  test("fail to start server when bloop.json has syntax error") {
    // Create invalid bloop.json file with a parse error.
    runBspLauncherWithGlobalJsonSettings("{") { result =>
      assert(result.status == Some(LauncherStatus.FailedToConnectToServer))
      assertLogsContain(
        List("Parse JSON file", "unexpected end of input"),
        result.launcherLogs
      )
    }
  }

  test("fail to start server when bloop.json Java home is invalid") {
    // Create bloop.json with Java home pointing to non-existent path.
    val doesnotexist = "does-not-exist"
    runBspLauncherWithGlobalJsonSettings(s"""{"javaHome": "$doesnotexist"}""") { result =>
      assert(result.status == Some(LauncherStatus.FailedToConnectToServer))
      assertLogsContain(
        List(doesnotexist, "Error when starting server"),
        result.launcherLogs
      )
    }
  }

  test("bloop.json javaOptions field is respected") {
    val customOptions = "-Dcustom.options=true"
    setupLauncherWithGlobalJsonSettings(
      s"""{"javaOptions": ["${customOptions}"]}"""
    ) { (run, status) =>
      assert(status == LauncherStatus.SuccessfulRun)
      assertLogsContain(
        List(customOptions),
        run.logs
      )
    }
  }
}
