package bloop

import bloop.cli.Commands
import bloop.logging.RecordingLogger
import bloop.testing.TestInternals
import bloop.util.TestUtil
import bloop.engine.Run
import org.junit.Assert.{assertFalse, assertTrue}
import org.junit.Test

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
}
