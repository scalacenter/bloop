package bloop.testing

import org.junit.Test
import org.junit.Assert.{assertFalse, assertTrue}

import bloop.engine.{Interpreter, Run}
import bloop.cli.Commands
import bloop.logging.RecordingLogger
import bloop.tasks.ProjectHelpers

class TestFilterSpec {

  private def neg(filter: String): String =
    s"-$filter"

  @Test
  def testFilterMatchesExactMatch: Unit = {
    val input = "foobar"
    val patterns = input :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertTrue("The filter should match on exact match.", filter(input))
  }

  @Test
  def testFilterDoesntMatchDifferentStrings: Unit = {
    val input = "helloworld"
    val patterns = "foobar" :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertFalse("The filter shouldn't match on unrelated input.", filter(input))
  }

  @Test
  def testFilterAcceptsSeveralPatterns: Unit = {
    val input0 = "helloworld"
    val input1 = "foobar"
    val patterns = input0 :: input1 :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertTrue("The filter should match.", filter(input0))
    assertTrue("The filter should match.", filter(input1))
  }

  @Test
  def testFilterSupportsWildcard: Unit = {
    val input0 = "foobar"
    val input1 = "fiesta"
    val input2 = "f"
    val patterns = "f*" :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertTrue("The filter should match.", filter(input0))
    assertTrue("The filter should match.", filter(input1))
    assertTrue("The filter should match.", filter(input2))
  }

  @Test
  def wildcardRejectsUnrelatedInput: Unit = {
    val input0 = "hello"
    val input1 = "world"
    val patterns = "f*" :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertFalse("The filter shouldn't match.", filter(input0))
    assertFalse("The filter shouldn't match.", filter(input1))
  }

  @Test
  def testFilterSupportsExclusionFilter: Unit = {
    val input = "foobar"
    val patterns = neg(input) :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertFalse("The filter shouldn't match.", filter(input))
  }

  @Test
  def testFilterSupportsWildcardInExclusionFilter: Unit = {
    val input0 = "foobar"
    val input1 = "fiesta"
    val input2 = "hello"
    val patterns = neg("f*") :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertFalse("The filter shouldn't match.", filter(input0))
    assertFalse("The filter shouldn't match.", filter(input1))
    assertTrue("The filter should match.", filter(input2))
  }

  @Test
  def testFilterSupportsInclusionAndExclusionFilters: Unit = {
    val input0 = "foo.hello.world"
    val input1 = "foo.something.world"
    val input2 = "bar.hello.world"
    val patterns = "*.world" :: neg("bar.*") :: Nil
    val filter = TestInternals.parseFilters(patterns)
    assertTrue("The filter should match.", filter(input0))
    assertTrue("The filter should match.", filter(input1))
    assertFalse("The filter shouldn't match.", filter(input2))
  }

  @Test
  def inclusionTestFiltersAreSupported: Unit = {
    val logger = new RecordingLogger
    val projectName = "with-tests"
    val testName = "hello.ScalaTestTest"
    val state = ProjectHelpers.loadTestProject(projectName).copy(logger = logger)
    val command = Run(Commands.Test(projectName, filter = testName :: Nil))
    val newState = Interpreter.execute(command, state)
    assertTrue("Test execution failed.", newState.status.isOk)
    val messages = logger.getMessages
    assertTrue(
      "No message about test inclusion found",
      messages.exists {
        case ("debug", msg) => msg.contains(s"included by the filter: $testName")
        case _ => false
      }
    )
  }

  @Test
  def exclusionTestFiltersAreSupported: Unit = {
    val logger = new RecordingLogger
    val projectName = "with-tests"
    val testName = "hello.ScalaTestTest"
    val exclusionFilter = s"-$testName"
    val state = ProjectHelpers.loadTestProject(projectName).copy(logger = logger)
    val command = Run(Commands.Test(projectName, filter = exclusionFilter :: Nil))
    val newState = Interpreter.execute(command, state)
    assertTrue("Test execution failed.", newState.status.isOk)
    val messages = logger.getMessages
    assertTrue(
      "No message about test exclusion found",
      messages.exists {
        case ("debug", msg) => msg.contains(s"excluded by the filter: $testName")
        case _ => false
      }
    )
  }

  @Test
  def combinedInclusionAndExclusionTestFiltersAreSupported: Unit = {
    val logger = new RecordingLogger
    val projectName = "with-tests"
    val exclusionFilter = "-hello.ScalaTest*"
    val inclusionFilter = "hello.*a*"
    val state = ProjectHelpers.loadTestProject(projectName).copy(logger = logger)
    val command = Run(
      Commands.Test(projectName, filter = exclusionFilter :: inclusionFilter :: Nil))
    val newState = Interpreter.execute(command, state)
    assertTrue("Test execution failed.", newState.status.isOk)
    val messages = logger.getMessages

    assertTrue(
      "No message about test inclusion found",
      messages.exists {
        case ("debug", msg) => msg.contains(s"included by the filter: hello.ScalaCheckTest")
        case _ => false
      }
    )
    assertTrue(
      "No message about test exclusion found",
      messages.exists {
        case ("debug", msg) =>
          msg.contains(s"excluded by the filter: ") &&
            msg.contains("hello.UTestTest") &&
            msg.contains("hello.Specs2Test") &&
            msg.contains("hello.ScalaTestTest") &&
            msg.contains("hello.WritingTest")
        case _ =>
          false
      }
    )
  }

}
