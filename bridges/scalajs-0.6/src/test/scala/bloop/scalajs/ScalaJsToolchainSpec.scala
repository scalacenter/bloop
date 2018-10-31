package bloop.scalajs

import bloop.cli.{Commands, OptimizerConfig}
import bloop.engine.Run
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

import bloop.data.Project
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class ScalaJsToolchainSpec {
  val state0 = TestUtil.loadTestProject("cross-platform", _.map(setUpScalajs))
  @Test def canLinkScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Link(project = "crossJS"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Generated JavaScript file '", atLevel = "info")
  }

  @Test def canLinkScalaJsProjectInReleaseMode(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Link(project = "crossJS", optimize = Some(mode)))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration * 2)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Inc. optimizer: Batch mode: true", atLevel = "debug")
  }

  @Test def canRunScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Run(project = "crossJS"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world!", atLevel = "info")
  }

  private def setUpScalajs(p: Project): Project = {
    p.jsToolchain match {
      case Some(_) => p
      case None => p.copy(jsToolchain = Some(ScalaJsToolchain.apply(this.getClass.getClassLoader)))
    }
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
