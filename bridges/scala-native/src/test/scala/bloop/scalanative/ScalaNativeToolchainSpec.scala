package bloop.scalanative

import bloop.cli.{Commands, OptimizerConfig}
import bloop.engine.Run
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

import bloop.data.{Platform, Project}
import bloop.engine.tasks.toolchains.{ScalaJsToolchain, ScalaNativeToolchain}
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class ScalaNativeToolchainSpec {
  private val state0 = {
    def setUpNative(p: Project): Project = {
      val platform = p.platform match {
        case nativePlatform: Platform.Native =>
          nativePlatform.copy(
            toolchain = Some(ScalaNativeToolchain.apply(this.getClass.getClassLoader)))
        case _ => p.platform
      }
      p.copy(platform = platform)
    }

    TestUtil.loadTestProject("cross-platform", _.map(setUpNative))
  }
  @Test def canLinkScalaNativeProject(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Debug
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Link(List("crossNative")))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Generated native binary '", atLevel = "info")
  }

  @Test def canLinkScalaNativeProjectInReleaseMode(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Link(List("crossNative"), optimize = Some(mode)))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration * 3)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Optimizing (release mode)", atLevel = "info")
  }

  @Test def canRunScalaNativeProject(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Debug
    val state = state0.copy(logger = logger)
    val action = Run(Commands.Run(List("crossNative")))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world!", atLevel = "info")
  }

  private val maxDuration = Duration.apply(30, TimeUnit.SECONDS)
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
