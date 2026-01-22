package bloop

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import bloop.cli.Commands
import bloop.engine.Run
import bloop.engine.State
import bloop.logging.RecordingLogger
import bloop.testing.BloopHelpers
import bloop.util.TestUtil
import org.junit.Test
import org.junit.experimental.categories.Category

@Category(Array(classOf[bloop.FastTests]))
class Issue882Spec extends BloopHelpers {
  @Test
  def reproduceIssue882(): Unit = {
    object Sources {
      val `A.scala` =
        """object Main extends App {
          |  val x = scala.io.StdIn.readInt()
          |  val y = scala.io.StdIn.readInt()
          |  println(x + y)
          |}
        """.stripMargin
    }

    val logger = new RecordingLogger
    val structure = Map("A" -> Map("A.scala" -> Sources.`A.scala`))
    TestUtil.testState(structure, Map.empty) { (state0: State) =>
      val input = "1\n2\n"
      val ourInputStream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
      val hijackedCommonOptions = state0.commonOptions.copy(in = ourInputStream)
      val state = state0.copy(logger = logger).copy(commonOptions = hijackedCommonOptions)
      val action = Run(Commands.Run(List("A")))
      val duration = Duration.apply(15, TimeUnit.SECONDS)

      val compiledState = TestUtil.blockingExecute(action, state, duration)

      val messages = logger.getMessages

      if (!compiledState.status.isOk) {
        messages.foreach(println)
      }

      assert(compiledState.status.isOk, "Run failed!")
      assert(
        messages.contains(("info", "3")),
        s"Messages did not contain outcome '3'. Found: $messages"
      )
    }
  }
}
