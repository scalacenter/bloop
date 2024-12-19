package bloop

import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import bloop.util.TestUtil
import bloop.logging.RecordingLogger
import bloop.cli.Commands

import bloop.testing.BaseSuite
import bloop.cli.OptimizerConfig
import bloop.engine.Run

object LinkSpec extends BaseSuite {

  val MainJSProject = "test-projectJS"
  val TestJSProject = "test-projectJS-test"
  private final val maxDuration = Duration.apply(60, TimeUnit.SECONDS)

  val jsState0 =
    TestUtil.loadTestProject(TestUtil.getBloopConfigDir("cross-test-build-scalajs-1.x"))

  test("can link scalajs1.x project") {

    val logger = new RecordingLogger
    val action = Run(Commands.Link(List(MainJSProject)))
    val resultingState =
      TestUtil.blockingExecute(action, jsState0.copy(logger = logger), maxDuration)

    assert(
      resultingState.status.isOk
    )
    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Generated JavaScript file"))
        .isDefined
    )
  }

  test("can link scalajs-1.x project in release mode") {
    val logger = new RecordingLogger
    val mode = OptimizerConfig.Release
    val state = jsState0.copy(logger = logger)
    val action = Run(Commands.Link(List(MainJSProject), optimize = Some(mode)))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration * 2)

    assert(resultingState.status.isOk)
    assert(
      logger
        .getMessagesAt(level = Some("debug"))
        .find(_.contains("Optimizer: Batch mode: true"))
        .isDefined
    )
  }

  test("can run scalaJS project") {
    val logger = new RecordingLogger
    val state = jsState0.copy(logger = logger)
    val action = Run(Commands.Run(List(MainJSProject)))
    val resultingState = TestUtil.blockingExecute(action, state, maxDuration)

    assert(resultingState.status.isOk)
    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Hello, world!"))
        .isDefined
    )
  }
}
