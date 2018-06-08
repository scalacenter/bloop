package bloop.scalajs

import bloop.{Project, ScalaInstance}
import bloop.cli.{Commands, OptimizerConfig}
import bloop.config.Config
import bloop.engine.{Run, State}
import bloop.exec.JavaEnv
import bloop.io.AbsolutePath
import bloop.logging.{Logger, RecordingLogger}
import bloop.tasks.TestUtil

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class ScalaJsToolchainSpec {

  @Test
  def canLinkScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val state = TestUtil
      .loadTestProject("cross-platform", _.map(setScalaJsConfig))
      .copy(logger = logger)
    val action = Run(Commands.Link(project = "crossJS"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Scala.js output written to:", atLevel = "info")
  }

  @Test
  def canLinkScalaJsProjectInReleaseMode(): Unit = {
    val logger = new RecordingLogger
    val state = TestUtil
      .loadTestProject("cross-platform", _.map(setScalaJsConfig))
      .copy(logger = logger)
    val action = Run(Commands.Link(project = "crossJS", optimize = OptimizerConfig.Release))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration * 2)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Inc. optimizer: Batch mode: true", atLevel = "debug")
  }

  @Test
  def canRunScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val state = TestUtil
      .loadTestProject("cross-platform", _.map(setScalaJsConfig))
      .copy(logger = logger)
    val action = Run(Commands.Run(project = "crossJS"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world!", atLevel = "info")
  }

  private val maxDuration = Duration.apply(30, TimeUnit.SECONDS)

  // Set a Scala JS Config with an empty classpath for the toolchain.
  // This works, because this module has the Scala.js toolchain on its classpath.
  private val setScalaJsConfig: Project => Project = {
    case prj if prj.platform == Config.Platform.JS =>
      prj.copy(jsConfig = Some(JsBridge.defaultJsConfig(prj)))
    case other =>
      other
  }

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
