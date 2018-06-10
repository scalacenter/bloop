package bloop.scalanative

import bloop.Project
import bloop.cli.{Commands, OptimizerConfig}
import bloop.config.Config
import bloop.engine.Run
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class ScalaNativeToolchainSpec {
  @Test def canLinkScalaNativeProject(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Debug
    val state = TestUtil.loadTestProject("cross-platform").copy(logger = logger)
    val action = Run(Commands.Link(project = "crossNative"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Scala Native binary:", atLevel = "info")
  }

  @Test def canLinkScalaNativeProjectInReleaseMode(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = TestUtil.loadTestProject("cross-platform").copy(logger = logger)
    val action = Run(Commands.Link(project = "crossNative", optimize = Some(mode)))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration * 2)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Optimizing (release mode)", atLevel = "info")
  }

  @Test def canRunScalaNativeProject(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Debug
    val state = TestUtil.loadTestProject("cross-platform").copy(logger = logger)
    val action = Run(Commands.Run(project = "crossNative"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world!", atLevel = "info")
  }

  private val maxDuration = Duration.apply(30, TimeUnit.SECONDS)

  private implicit class RichLogs(logs: List[(String, String)]) {
    def assertContain(needle: String, atLevel: String): Unit = {
      def failMessage = s"""Logs didn't contain `$needle` at level `$atLevel`. Logs were:
                           |${logs.mkString("\n")}""".stripMargin
      assertTrue(failMessage, logs.exists {
        case (`atLevel`, msg) => msg.contains(needle)
        case _ => false
      })
    }
  }
}
