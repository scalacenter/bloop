package hello

import org.junit.Test
import org.junit.Assert.assertEquals

class JUnitTest {
  @Test
  def myTest: Unit = {
    assertEquals(4, 2 + 2)
  }
}
