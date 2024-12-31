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
  val MainNativeProject = "test-projectNative"
  val TestNativeProject = "test-projectNative-test"

  private final val maxDuration = Duration.apply(60, TimeUnit.SECONDS)

  val jsState0 =
    TestUtil.loadTestProject(TestUtil.getBloopConfigDir("cross-test-build-scalajs-1.x"))

  val native05State0 =
    TestUtil.loadTestProject(TestUtil.getBloopConfigDir("cross-test-build-scala-native-0.5"))

  val native04State0 =
    TestUtil.loadTestProject(TestUtil.getBloopConfigDir("cross-test-build-scala-native-0.4"))

  ////////////////////////////////////////////////
  ////// ScalaJS
  ////////////////////////////////////////////////

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

  ////////////////////////////////////////////////
  ////// ScalaNative 04
  ////////////////////////////////////////////////

  test("can link scala-native 04 project") {
    val logger = new RecordingLogger
    val state = native04State0.copy(logger = logger)
    val action = Run(Commands.Link(List(MainNativeProject)))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Generated native binary "))
        .isDefined
    )
  }

  test("can link scala-native 04 project in release mode") {
    val logger = new RecordingLogger
    val state = native04State0.copy(logger = logger)
    val mode = OptimizerConfig.Release
    val action = Run(Commands.Link(List(MainNativeProject), optimize = Some(mode)))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Optimizing (release-fast mode) "))
        .isDefined
    )
  }

  test("can run scala-native 04 project") {
    val logger = new RecordingLogger
    val state = native04State0.copy(logger = logger)
    val action = Run(Commands.Run(List(MainNativeProject)))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Hello, world from DefaultApp!"))
        .isDefined
    )
  }

  test("can run scala-jvm 04 project") {
    val logger = new RecordingLogger
    val state = native04State0.copy(logger = logger)
    val action = Run(Commands.Run(List("test-project"), main = None))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Hello, world!"))
        .isDefined
    )
  }
  ////////////////////////////////////////////////
  ////// ScalaNative 05
  ////////////////////////////////////////////////

  test("can link scala-native 05 project") {
    val logger = new RecordingLogger
    val state = native05State0.copy(logger = logger)
    val action = Run(Commands.Link(List(MainNativeProject)))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Generated native binary "))
        .isDefined
    )
  }

  test("can link scala-native 05 project in release mode") {
    val logger = new RecordingLogger
    val state = native05State0.copy(logger = logger)
    val mode = OptimizerConfig.Release
    val action = Run(Commands.Link(List(MainNativeProject), optimize = Some(mode)))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Optimizing (release-fast mode) "))
        .isDefined
    )
  }

  test("can run scala-native 05 project") {
    val logger = new RecordingLogger
    val state = native05State0.copy(logger = logger)
    val action = Run(Commands.Run(List(MainNativeProject)))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Hello, world from DefaultApp!"))
        .isDefined
    )
  }

  test("can run scala-jvm 05 project") {
    val logger = new RecordingLogger
    val state = native05State0.copy(logger = logger)
    val action = Run(Commands.Run(List("test-project"), main = None))
    val resultingState =
      TestUtil.blockingExecute(action, state, maxDuration)

    assert(
      resultingState.status.isOk
    )

    assert(
      logger
        .getMessagesAt(level = Some("info"))
        .find(_.contains("Hello, world!"))
        .isDefined
    )
  }
}
