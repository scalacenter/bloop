package bloop.nailgun

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import bloop.internal.build.BuildInfo
import bloop.io.AbsolutePath
import bloop.io.Environment.lineSeparator
import bloop.io.RelativePath
import bloop.logging.RecordingLogger
import bloop.testing.BaseSuite
import bloop.util.JavaRuntime
import bloop.util.TestUtil

object NailgunSpec extends BaseSuite with NailgunTestUtils {
  val workspace: AbsolutePath = AbsolutePath(Files.createTempDirectory("bloop-test-workspace"))
  val simpleBuild: TestBuild =
    loadBuildFromResources("simple-build", workspace, new RecordingLogger)
  val configDir = simpleBuild.state.build.origin.underlying

  val jvmLine: String =
    s"Running on Java ${JavaRuntime.current} v${JavaRuntime.version} (${JavaRuntime.home})"

  def withServerInProject[T](op: (RecordingLogger, Client) => T): T =
    withServer(configDir, false, new RecordingLogger(ansiCodesSupported = false))(op)

  def withServerInProject[T](noExit: Boolean)(
      op: (RecordingLogger, Client) => T
  ): T = withServer(configDir, noExit, new RecordingLogger(ansiCodesSupported = false))(op)

  private def joinLinesForDiff(lines: Seq[String]): String =
    lines
      .filter(!_.contains("DEBUG"))
      .map(_.trim)
      .filter(_.nonEmpty)
      .mkString(lineSeparator)

  def assertNoErrors(logger: RecordingLogger): Unit = {
    assertNoDiff(joinLinesForDiff(logger.errors), "")
  }

  test("nailgun help works in simple build") {
    TestUtil.retry() {
      simpleBuildNailgunHelpTest()
    }
  }
  def simpleBuildNailgunHelpTest(): Unit = {
    withServerInProject { (logger, client) =>
      client.expectSuccess("help")
      assertNoErrors(logger)
      assertNoDiff(
        joinLinesForDiff(logger.infos),
        s"""|bloop ${BuildInfo.version}
            |Usage: bloop [options] [command] [command-options]
            |Available commands: about, autocomplete, bsp, clean, compile, configure, console, help, link, projects, run, test
            |Type `bloop 'command' --help` for help on an individual command""".stripMargin
      )
    }
  }

  test("nailgun fails if command doesn't exist") {
    TestUtil.retry() {
      nailgunFailsIfCommandDoesntExist()
    }
  }
  def nailgunFailsIfCommandDoesntExist(): Unit = {
    withServerInProject { (logger, client) =>
      client.expectFailure("foobar")
      logger.dump()
      assertNoErrors(logger)
      assertNoDiff(
        joinLinesForDiff(logger.infos),
        "Command not found: foobar"
      )
    }
  }

  test("nailgun help works in empty build") {
    TestUtil.retry() {
      emptyBuildNailgunHelpTest()
    }
  }
  def emptyBuildNailgunHelpTest(): Unit = {
    TestUtil.withinWorkspace { workspace =>
      import java.nio.file.Files
      val configDir = Files.createDirectories(workspace.resolve(".bloop").underlying)
      val logger = new RecordingLogger(ansiCodesSupported = false)
      withServer(configDir, false, logger) { (logger, client) =>
        client.expectSuccess("help")
        assertNoErrors(logger)
        assertNoDiff(
          joinLinesForDiff(logger.infos),
          s"""|bloop ${BuildInfo.version}
              |Usage: bloop [options] [command] [command-options]
              |Available commands: about, autocomplete, bsp, clean, compile, configure, console, help, link, projects, run, test
              |Type `bloop 'command' --help` for help on an individual command""".stripMargin
        )
      }
    }
  }

  test("nailgun about works in simple build") {
    TestUtil.retry() {
      nailgunAboutWorksInSimpleBuild()
    }
  }
  def nailgunAboutWorksInSimpleBuild(): Unit = {
    withServerInProject { (logger, client) =>
      client.expectSuccess("about")
      assertNoErrors(logger)
      assertNoDiff(
        joinLinesForDiff(logger.infos),
        s"""|bloop v${BuildInfo.version}
            |Using Scala v${BuildInfo.scalaVersion} and Zinc v${BuildInfo.zincVersion}
            |$jvmLine
            |-> Supports debugging user code, Java Debug Interface (JDI) is available.
            |Maintained by the Scala Center and the community.""".stripMargin
      )
    }
  }

  test("nailgun projects works in simple build") {
    TestUtil.retry() {
      nailgunProjectsWorksInSimpleBuild()
    }
  }
  def nailgunProjectsWorksInSimpleBuild(): Unit = {
    withServerInProject { (logger, client) =>
      client.expectSuccess("projects")
      assertNoErrors(logger)
      assertNoDiff(
        joinLinesForDiff(logger.infos),
        """|a
           |a-test
           |b
           |b-test
           |simple-build
           |simple-build-test""".stripMargin
      )
    }
  }

  test("nailgun projects works in simple build referenced from other cwd") {
    TestUtil.retry() {
      nailgunProjectsWorksInSimpleBuildReferencedFromOtherCwd()
    }
  }
  def nailgunProjectsWorksInSimpleBuildReferencedFromOtherCwd(): Unit = {
    withServerInProject { (logger, client) =>
      TestUtil.withinWorkspace { workspace =>
        val externalClient = Client(super.TEST_PORT, logger, workspace.underlying)
        val clientConfig = client.config.toAbsolutePath().toString
        val process = externalClient.issueAsProcess("projects", "--config-dir", clientConfig)
        process.waitFor(1, TimeUnit.SECONDS)
      }

      assertNoErrors(logger)
      assertNoDiff(
        joinLinesForDiff(logger.infos),
        """|a
           |a-test
           |b
           |b-test
           |simple-build
           |simple-build-test""".stripMargin
      )
    }
  }

  test("nailgun about works in build that doesn't load, but listing projects fails") {
    TestUtil.retry() {
      nailgunAboutWorksInBuildThatDoesntLoadListingProjectsFails()
    }
  }
  def nailgunAboutWorksInBuildThatDoesntLoadListingProjectsFails(): Unit = {
    val configDir = TestUtil.createSimpleRecursiveBuild(RelativePath(".bloop")).underlying
    val logger = new RecordingLogger(ansiCodesSupported = false)
    withServer(configDir, false, logger) { (logger, client) =>
      client.expectSuccess("about")
      client.expectFailure("projects", "--no-color")
      assertNoDiff(
        joinLinesForDiff(logger.infos),
        s"""|bloop v${BuildInfo.version}
            |Using Scala v${BuildInfo.scalaVersion} and Zinc v${BuildInfo.zincVersion}
            |$jvmLine
            |-> Supports debugging user code, Java Debug Interface (JDI) is available.
            |Maintained by the Scala Center and the community.""".stripMargin
      )

      assertNoDiff(
        joinLinesForDiff(logger.errors),
        "[E] Fatal recursive dependency detected in 'g': List(g, g)"
      )
    }
  }

  test("nailgun compile works in simple build") {
    TestUtil.retry() {
      nailgunCompileWorksInSimpleBuild()
    }
  }
  def nailgunCompileWorksInSimpleBuild(): Unit = {
    withServerInProject { (logger, client) =>
      client.expectSuccess("clean", "b", "--propagate")
      client.expectSuccess("compile", "b")
      client.expectSuccess("clean", "-p", "b", "--propagate")
      client.expectSuccess("compile", "-p", "b")
      assertNoErrors(logger)
      assertNoDiff(
        joinLinesForDiff(
          logger.captureTimeInsensitiveInfos
            .filterNot(msg =>
              msg.startsWith("Non-compiled module") ||
                msg.startsWith(" Compilation completed in")
            )
        ),
        """|Compiling a (1 Scala source)
           |Compiled a ???
           |Compiling b (1 Scala source)
           |Compiled b ???
           |Compiling a (1 Scala source)
           |Compiled a ???
           |Compiling b (1 Scala source)
           |Compiled b ???""".stripMargin
      )
    }

    val newLogger = new RecordingLogger(ansiCodesSupported = false)
    withServer(configDir, false, newLogger) { (_, client) =>
      // Add change to configuration file of project
      val configFile = configDir.resolve("b.json")
      val jsonContents = new String(Files.readAllBytes(configFile), UTF_8)
      val newContents = jsonContents + " "
      Files.write(configFile, newContents.getBytes(UTF_8))

      // Checks new nailgun session still produces a no-op compilation
      client.expectSuccess("compile", "b")
      assertNoDiff(joinLinesForDiff(newLogger.captureTimeInsensitiveInfos), "")
    }
  }

  override def utestAfterAll(): Unit = {
    // Make sure that we never end up with a background nailgun server running
    val cwd = Paths.get(System.getProperty("user.dir"))
    val client = Client(super.TEST_PORT, new RecordingLogger(), cwd)
    val process = client.issueAsProcess("exit", "--soft")
    process.waitFor(1, TimeUnit.SECONDS)

    bloop.io.Paths.delete(workspace)
    ()
  }
}
