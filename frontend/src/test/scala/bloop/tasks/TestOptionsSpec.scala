package bloop.tasks

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test

import bloop.cli.{Commands, ExitStatus}
import bloop.engine.{Interpreter, Run}
import bloop.logging.RecordingLogger

class TestOptionsSpec {
  final val ProjectName = "with-tests"

  @Test
  def exclusionsInTestOptionsAreRespected: Unit = {
    val logger = new RecordingLogger
    val state = ProjectHelpers.loadTestProject(ProjectName).copy(logger = logger)
    val action = Run(Commands.Test(ProjectName))
    val newState = Interpreter.execute(action, state)

    assertEquals(newState.status, ExitStatus.Ok)

    val needle = ("debug", "The following tests were excluded by the filter: hello.WritingTest")
    val messages = logger.getMessages

    assertTrue(s"""Message not found: $needle
                  |Logs: $messages""".stripMargin,
               messages.contains(needle))
  }

  @Test
  def testOptionsArePassed: Unit = {
    val logger = new RecordingLogger
    val state = ProjectHelpers.loadTestProject(ProjectName).copy(logger = logger)
    val action = Run(Commands.Test(ProjectName, filter = "hello.JUnitTest" :: Nil))
    val newState = Interpreter.execute(action, state)

    // The levels won't be correct, and the messages won't match
    // because of output coloring if the test options are ignored.
    val needle1 = ("info", "Test run started")
    val needle2 = ("info", "Test hello.JUnitTest.myTest started")
    val needle3 = ("debug", "Test hello.JUnitTest.myTest finished")
    val messages = logger.getMessages

    assertEquals(newState.status, ExitStatus.Ok)

    assertTrue(s"""Message not found: $needle1
                  |Logs: $messages""".stripMargin,
               messages.contains(needle1))
    assertTrue(s"""Message not found: $needle2
                  |Logs: $messages""".stripMargin,
               messages.contains(needle1))
    assertTrue(
      s"""Message not found: $needle3
         |Logs: $messages""".stripMargin,
      messages.exists {
        case (level, msg) => level == needle3._1 && msg.startsWith(needle3._2)
      }
    )
  }
}
