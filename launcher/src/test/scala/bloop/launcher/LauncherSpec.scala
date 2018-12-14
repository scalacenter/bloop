package bloop.launcher

import java.nio.file.{Files, Path, Paths}

import org.junit.{Assert, Test}

import scala.util.control.NonFatal

class LauncherSpec extends AbstractLauncherSpec {
  private final val bloopVersion = "1.1.2"
  private class LauncherFailure extends Exception("The bloop launcher didn't finish successfully.")
  val successfulCliExit = (successful: Boolean) => if (successful) () else throw new LauncherFailure

  case class LauncherRun(successful: Boolean, logs: List[String])
  def runLauncher(launcherLogic: LauncherMain => Boolean): LauncherRun = {
    import java.io.ByteArrayOutputStream
    import java.io.PrintStream
    import java.nio.charset.StandardCharsets
    val baos = new ByteArrayOutputStream()
    val ps = new PrintStream(baos, true, "UTF-8")
    try {
      val port = Utils.portNumberWithin(8997, 9002)
      val launcher = new LauncherMain(ps, Some(port))
      val successful = launcherLogic(launcher)
      val logs = new String(baos.toByteArray, StandardCharsets.UTF_8)
      LauncherRun(successful, logs.split(System.lineSeparator()).toList)
    } finally if (ps != null) ps.close()
  }

  def runCli(args: Array[String]): LauncherRun = {
    runLauncher { launcher =>
      var successful: Boolean = false
      launcher.cli(args, runSuccessfully => if (runSuccessfully) successful = true else ())
      successful
    }
  }

  def testLauncher[T](run: LauncherRun)(testFunction: LauncherRun => T): Unit = {
    try {
      testFunction(run)
      ()
    } catch {
      case NonFatal(t) =>
        System.out.println(run.logs.map(l => s"> $l").mkString(System.lineSeparator()))
        throw t
    }
  }
  @Test
  def testSystemPropertiesMockingWork(): Unit = {
    // Test from https://stefanbirkner.github.io/system-rules/index.html
    val parentDir = Files.createTempDirectory("boooo").getParent
    parentDir.toFile.deleteOnExit()
    Assert.assertEquals(parentDir, Paths.get(System.getProperty("user.dir")).getParent)
    Assert.assertEquals(parentDir, Paths.get(System.getProperty("user.home")).getParent)
  }

  @Test
  def failIfEmptyArguments(): Unit = {
    testLauncher(runCli(Array.empty)) { run =>
      Assert.assertTrue("Expected failed bloop launcher", !run.successful)
      val errorMsg = "The bloop launcher accepts only one argument: the bloop version"
      Assert.assertTrue(s"Missing '${errorMsg}'", run.logs.exists(_.contains(errorMsg)))
    }
  }

  @Test
  def checkThatPythonIsInClasspath(): Unit = {
    val run = runLauncher { launcher =>
      launcher.isPythonInClasspath
    }

    testLauncher(run) { run =>
      Assert.assertTrue(run.successful)
    }
  }

  @Test
  def dontDetectSystemBloop(): Unit = {
    val run = runLauncher { launcher =>
      // We should not detect the server state unless we have installed it via the launcher
      val state = launcher.detectServerState(bloopVersion)
      if (state == None) true
      else {
        launcher.out.println(s"Found bloop binary in ${state}, expected none!")
        false
      }
    }

    testLauncher(run) { run =>
      Assert.assertTrue(run.successful)
    }
  }

  @Test
  def testInstallationViaInstallpy(): Unit = {
    val run = runLauncher { launcher =>
      // Install the launcher via `install.py`, which is the preferred installation method
      val tempDir = Files.createTempDirectory("bloop-install")
      val state = Installer.installBloopBinaryInHomeDir(
        tempDir,
        launcher.defaultBloopDirectory,
        bloopVersion,
        launcher.out,
        launcher.detectServerState(_)
      )

      // We should detect the bloop binary in the place where we installed it!
      val bloopDir = launcher.defaultBloopDirectory.resolve("bloop")
      if (state == Some(AvailableAt(bloopDir.toString))) {
        deleteRecursively(tempDir)
        true
      } else {
        deleteRecursively(tempDir)
        launcher.out.println(s"The installation in ${bloopDir} didn't succeed, obtained ${state}!")
        false
      }
    }

    testLauncher(run) { run =>
      Assert.assertTrue("The exit code of the installation was not 0", run.successful)
    }
  }

  @Test
  def testBloopResolution(): Unit = {
    val run = runLauncher { launcher =>
      val (_, resolution) = Installer.resolveServer(bloopVersion, true)
      Assert.assertTrue(s"Resolution errors ${resolution.errors}", resolution.errors.isEmpty)
      Installer.fetchJars(resolution, launcher.out).nonEmpty
    }

    testLauncher(run) { run =>
      Assert.assertTrue("Jars were not fetched!", run.successful)
    }
  }
}
