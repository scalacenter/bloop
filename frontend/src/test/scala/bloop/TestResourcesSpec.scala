package bloop

import bloop.cli.Commands
import bloop.util.TestUtil.{loadTestProject, runAndCheck}
import org.junit.Test
import org.junit.experimental.categories.Category
import sbt.internal.util.EscHelpers.removeEscapeSequences

@Category(Array(classOf[bloop.FastTests]))
class TestResourcesSpec {
  @Test
  def testsCanSeeResources: Unit = {
    val projectName = "with-resources"
    val state = loadTestProject(projectName)
    val command = Commands.Test(List(projectName))
    runAndCheck(state, command) { messages =>
      val cleanMessages = messages.map { case (l, m) => (l, removeEscapeSequences(m)) }
      assert(cleanMessages.contains(("info", "Resources")))
      assert(cleanMessages.contains(("info", "- should be found")))
    }
  }
}
