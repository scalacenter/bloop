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

  @Test
  def verifyReadLine(): Unit = {
    object Sources {
      val `B.scala` =
        """object Main extends App {
          |  val l1 = scala.io.StdIn.readLine()
          |  val l2 = scala.io.StdIn.readLine()
          |  println(l1 + " " + l2)
          |}
        """.stripMargin
    }

    val logger = new RecordingLogger
    val structure = Map("B" -> Map("B.scala" -> Sources.`B.scala`))
    TestUtil.testState(structure, Map.empty) { (state0: State) =>
      val input = "Hello\nWorld\n"
      val ourInputStream = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8))
      val hijackedCommonOptions = state0.commonOptions.copy(in = ourInputStream)
      val state = state0.copy(logger = logger).copy(commonOptions = hijackedCommonOptions)
      val action = Run(Commands.Run(List("B")))
      val duration = Duration.apply(15, TimeUnit.SECONDS)

      val compiledState = TestUtil.blockingExecute(action, state, duration)
      val messages = logger.getMessages

      assert(compiledState.status.isOk, "Run failed!")
      assert(
        messages.contains(("info", "Hello World")),
        s"Messages did not contain outcome 'Hello World'. Found: $messages"
      )
    }
  }

  @Test
  def verifyLargeInput(): Unit = {
    object Sources {
      val `C.scala` =
        """object Main extends App {
          |  Iterator.continually(scala.io.StdIn.readLine())
          |    .takeWhile(_ != "END")
          |    .foreach(println)
          |}
        """.stripMargin
    }

    val logger = new RecordingLogger
    val structure = Map("C" -> Map("C.scala" -> Sources.`C.scala`))
    TestUtil.testState(structure, Map.empty) { (state0: State) =>
      val lines = (1 to 100).map(i => s"Line $i").mkString("\n") + "\nEND\n"
      val ourInputStream = new ByteArrayInputStream(lines.getBytes(StandardCharsets.UTF_8))
      val hijackedCommonOptions = state0.commonOptions.copy(in = ourInputStream)
      val state = state0.copy(logger = logger).copy(commonOptions = hijackedCommonOptions)
      val action = Run(Commands.Run(List("C")))
      val duration = Duration.apply(30, TimeUnit.SECONDS)

      val compiledState = TestUtil.blockingExecute(action, state, duration)
      val messages = logger.getMessages

      assert(compiledState.status.isOk, "Run failed!")
      assert(messages.contains(("info", "Line 1")), "Missing start")
      assert(messages.contains(("info", "Line 50")), "Missing middle")
      assert(messages.contains(("info", "Line 100")), "Missing end")
    }
  }
}
