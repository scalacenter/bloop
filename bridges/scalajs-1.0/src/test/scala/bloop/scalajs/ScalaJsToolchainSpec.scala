package bloop.scalajs

import bloop.cli.{Commands, OptimizerConfig}
import bloop.engine.{Run, State}
import bloop.logging.RecordingLogger
import bloop.tasks.TestUtil

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit

import bloop.data.{Platform, Project}
import bloop.engine.tasks.toolchains.ScalaJsToolchain
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class ScalaJsToolchainSpec {
  val NormalMain = Some("hello.App")
  val MainProject = "test-projectJS"
  val TestProject = "test-projectJS-test"
  val CommonJsProject = "commonjs-project"
  val crossTestState: State = {
    def setUpScalajs(p: Project): Project = {
      val platform = p.platform match {
        case jsPlatform: Platform.Js =>
          jsPlatform.copy(toolchain = Some(ScalaJsToolchain.apply(this.getClass.getClassLoader)))
        case _ => p.platform
      }
      p.copy(platform = platform)
    }

    TestUtil.loadTestProject("cross-test-build-1.0", _.map(setUpScalajs))
  }

  @Test def canLinkScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val state = crossTestState.copy(logger = logger)
    val action = Run(Commands.Link(project = MainProject, main = NormalMain))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Generated JavaScript file '", atLevel = "info")
  }

  @Test def canLinkCommonJsScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val state = crossTestState.copy(logger = logger)
    val action = Run(Commands.Link(project = CommonJsProject, main = NormalMain))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Generated JavaScript file '", atLevel = "info")
  }

  @Test def canLinkScalaJsProjectInReleaseMode(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = crossTestState.copy(logger = logger)
    val action = Run(Commands.Link(project = MainProject, optimize = Some(mode), main = NormalMain))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration * 2)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Inc. optimizer: Batch mode: true", atLevel = "debug")
  }

  @Test def canRunScalaJsProject(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = crossTestState.copy(logger = logger)
    val action = Run(Commands.Run(project = MainProject, main = NormalMain))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world!", atLevel = "info")
  }

  @Test def canRunScalaJsProjectDefaultMainClass(): Unit = {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = crossTestState.copy(logger = logger)
    val action = Run(Commands.Run(project = MainProject, main = None))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)
    logger.getMessages.assertContain("Hello, world from DefaultApp!", atLevel = "info")
  }

  private final val maxDuration = Duration.apply(45, TimeUnit.SECONDS)
  private implicit class RichLogs(logs: List[(String, String)]) {
    def assertContain(needle: String, atLevel: String): Unit = {
      def failMessage: String =
        s"""Logs did not contain `$needle` at level `$atLevel`. Logs were:
           |${logs.mkString("\n")}""".stripMargin
      assertTrue(failMessage, logs.exists {
        case (`atLevel`, msg) => msg.contains(needle)
        case _ => false
      })
    }
  }
}
