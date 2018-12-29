package bloop.testing

import org.junit.Assert.{assertEquals, assertTrue}
import org.junit.Test
import bloop.cli.{Commands, ExitStatus}
import bloop.engine.Run
import bloop.logging.RecordingLogger
import bloop.util.TestUtil

class TestOptionsSpec {
  final val ProjectName = "with-tests"

  @Test
  def exclusionsInTestOptionsAreRespected(): Unit = {
    val logger = new RecordingLogger
    val state = TestUtil.loadTestProject(ProjectName).copy(logger = logger)
    val action = Run(Commands.Test(List(ProjectName)))
    val newState = TestUtil.blockingExecute(action, state)

    assertEquals(newState.status, ExitStatus.Ok)

    val needle = ("debug", "The following tests were excluded by the filter: hello.WritingTest")
    val messages = logger.getMessages

    assertTrue(
      s"""Message not found: $needle
         |Logs: $messages""".stripMargin,
      messages.contains(needle))
  }

  case class TestMsg(level: String, msg: String)

  @Test
  def testOptionsArePassed(): Unit = {
    val logger = new RecordingLogger
    val state = TestUtil.loadTestProject(ProjectName).copy(logger = logger)
    val junitArgs = List("-a")
    val action = Run(
      Commands.Test(List(ProjectName), only = "hello.JUnitTest" :: Nil, args = junitArgs))
    val newState = TestUtil.blockingExecute(action, state)

    // The levels won't be correct, and the messages won't match
    // because of output coloring if the test options are ignored.
    val needle1 = TestMsg("info", "Test run started")
    val needle2 = TestMsg("info", "Test hello.JUnitTest.myTest started")
    val needle3 = TestMsg("debug", "Test hello.JUnitTest.myTest finished")
    val missingNeedle4 = ("debug", "Framework-specific test options")
    val messages = logger.getMessages

    def assertMsgStartsWith(toTest: TestMsg) = {
      assertTrue(
        s"Message doesn't exist: $toTest;\nLogs: $messages",
        messages.exists { case (level, msg) => level == toTest.level && msg.startsWith(toTest.msg) }
      )
    }

    assertEquals(newState.status, ExitStatus.Ok)
    assertMsgStartsWith(needle1)
    assertMsgStartsWith(needle2)
    assertMsgStartsWith(needle3)
    assertTrue("Junit test options are ignored!", !messages.contains(missingNeedle4))

    // Will need to be changed when the new test suite report format is merged
    assertMsgStartsWith(TestMsg("info", "Total duration:"))
    assertMsgStartsWith(TestMsg("info", "All 1 test suites passed"))
  }
}
