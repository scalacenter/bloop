package bloop.scalanative

import bloop.{DependencyResolution, Project, ScalaInstance}
import bloop.cli.Commands
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
class ScalaNativeSpec {

  @Test
  def canLinkScalaNativeProject(): Unit = {
    val logger = new RecordingLogger
    val state = TestUtil
      .loadTestProject("cross-platform", _.map(setScalaNativeClasspath))
      .copy(logger = logger)
    val action = Run(Commands.Link(project = "crossNative"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assertTrue(s"Linking failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)

    val needle = "Scala Native binary:"
    assertTrue(
      s"Logs didn't contain '$needle': ${logger.getMessages.mkString("\n")}",
      logger.getMessages.exists {
        case ("info", msg) => msg.startsWith(needle)
        case _ => false
      }
    )
  }

  @Test
  def canRunScalaNativeProject(): Unit = {
    val logger = new RecordingLogger
    val state = TestUtil
      .loadTestProject("cross-platform", _.map(setScalaNativeClasspath))
      .copy(logger = logger)
    val action = Run(Commands.Run(project = "crossNative"))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)
    assertTrue(s"Run failed: ${logger.getMessages.mkString("\n")}", resultingState.status.isOk)

    val needle = "Hello, world!"
    assertTrue(
      s"Logs didn't contain '$needle': ${logger.getMessages.mkString("\n")}",
      logger.getMessages.exists {
        case ("info", msg) => msg.startsWith(needle)
        case _ => false
      }
    )
  }

  private val maxDuration = Duration.apply(30, TimeUnit.SECONDS)

  // Set a dummy `nativeClasspath` for the Scala Native toolchain.
  // This is to avoid trying to resolve the toolchain with Coursier,
  // and will work because the toolchain is on this module's classpath.
  private val setScalaNativeClasspath: Project => Project = {
    case prj if prj.platform == Config.Platform.Native =>
      prj.copy(nativeConfig = Some(NativeBridge.defaultNativeConfig(prj)))
    case other =>
      other
  }

}
