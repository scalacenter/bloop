package bloop.scalajs

import bloop.cli.{Commands, OptimizerConfig}
import bloop.engine.{Run, State}
import bloop.logging.RecordingLogger

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

import bloop.data.{Platform, Project}
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import bloop.util.TestUtil
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class ScalaJsToolchainSpec {
  val MainProject = "test-projectJS"
  val TestProject = "test-projectJS-test"
  val state0: State = {
    def setUpScalajs(p: Project): Project = {
      val platform = p.platform match {
        case jsPlatform: Platform.Js =>
          // Let's use the scalajs toolchain that is present in this test classloader
          jsPlatform.copy(toolchain = Some(ScalaJsToolchain.apply(this.getClass.getClassLoader)))
        case _ => p.platform
      }
      p.copy(platform = platform)
    }

    val configDir = TestUtil.getBloopConfigDir("cross-test-build-0.6")
    val logger = bloop.logging.BloopLogger.default(configDir.toString())
    TestUtil.loadTestProject(configDir, logger, true, _.map(setUpScalajs))
  }

  @Test def canLinkScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Link(List(MainProject), main = Some("hello.App")))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Generated JavaScript file '", atLevel = "info")
  }

  @Test def canLinkScalaJsProjectInReleaseMode(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = state0.copy(logger = logger)
    val action = Run(
      Commands.Link(List(MainProject), optimize = Some(mode), main = Some("hello.App"))
    )
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration * 2)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Inc. optimizer: Batch mode: true", atLevel = "debug")
  }

  @Test def canRunScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Run(List(MainProject), main = Some("hello.App")))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world!", atLevel = "info")
  }

  @Test def canRunScalaJsProjectDefaultMainClass(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Run(List(MainProject), main = None))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world from DefaultApp!", atLevel = "info")
  }

  private final val maxDuration = Duration.apply(45, TimeUnit.SECONDS)
  private implicit class RichLogs(logs: List[(String, String)]) {
    def assertContain(needle: String, atLevel: String): Unit = {
      def failMessage = s"""Logs did not contain `$needle` at level `$atLevel`. Logs were:
                           |${logs.mkString("\n")}""".stripMargin
      assertTrue(failMessage, logs.exists {
        case (`atLevel`, msg) => msg.contains(needle)
        case _ => false
      })
    }
  }
}
