package bloop.tasks

import org.junit.Test
import org.junit.experimental.categories.Category

import sbt.internal.util.EscHelpers.removeEscapeSequences

import bloop.cli.Commands
import bloop.tasks.TestUtil.{loadTestProject, runAndCheck}

@Category(Array(classOf[bloop.FastTests]))
class TestResourcesSpec {
  @Test
  def testsCanSeeResources: Unit = {
    val projectName = "with-resources"
    val state = loadTestProject(projectName)
    val command = Commands.Test(projectName)
    runAndCheck(state, command) { messages =>
      val cleanMessages = messages.map { case (l, m) => (l, removeEscapeSequences(m)) }
      assert(cleanMessages.contains(("info", "Resources")))
      assert(cleanMessages.contains(("info", "- should be found")))
    }
  }
}
