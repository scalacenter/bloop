package bloop.nailgun

import bloop.logging.RecordingLogger
import bloop.tasks.ProjectHelpers

import org.junit.Test
import org.junit.Assert.{assertEquals, assertTrue}

class BasicNailgunSpec extends NailgunTest {

  @Test
  def unknownCommandTest(): Unit = {
    val logger = new RecordingLogger
    val base = ProjectHelpers.getTestProjectDir("with-resources")
    withServer(logger, base) { client =>
      val command = "thatcommanddoesntexist"
      client.fail("thatcommanddoesntexist")
      val messages = logger.getMessages()
      assertTrue("Error was not reported in $messages",
                 messages.contains(("info", s"Command not found: $command")))
    }
  }

  @Test
  def projectsCommandTest(): Unit = {
    val logger = new RecordingLogger
    val base = ProjectHelpers.getTestProjectDir("with-resources")
    withServer(logger, base) { client =>
      client.success("projects")
      val messages = logger.getMessages()
      val expectedProjects = "with-resources" :: "with-resources-test" :: Nil

      expectedProjects.foreach { proj =>
        val needle = s" * $proj"
        assertTrue(s"$messages didn't contain $needle'", messages.contains(("info", needle)))
      }
    }
  }

}
