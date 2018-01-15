package bloop.tasks

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.experimental.categories.Category

import sbt.internal.util.EscHelpers.removeEscapeSequences

import bloop.cli.Commands
import bloop.exec.JavaEnv
import bloop.tasks.ProjectHelpers.{loadTestProject, runAndCheck}

@Category(Array(classOf[bloop.FastTests]))
class TestResourcesSpec {

  @Test
  def testsCanSeeResourcesWithoutForking = testsCanSeeResources(fork = false)

  @Test
  def testsCanSeeResourcesWithForking = testsCanSeeResources(fork = true)

  private def testsCanSeeResources(fork: Boolean): Unit = {
    val projectName = "with-resources"
    val state0 = loadTestProject(projectName)
    val state = {
      if (!fork) state0
      else {
        val newProjects = state0.build.projects.map(_.copy(javaEnv = JavaEnv.default(fork = true)))
        state0.copy(build = state0.build.copy(projects = newProjects))
      }
    }

    val command = Commands.Test(projectName)
    runAndCheck(state, command) { messages =>
      val cleanMessages = messages.map { case (l, m) => (l, removeEscapeSequences(m)) }
      assert(cleanMessages.contains(("info", "Resources")))
      assert(cleanMessages.contains(("info", "- should be found")))
    }
  }
}
