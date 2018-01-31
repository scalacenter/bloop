package bloop.nailgun

import bloop.logging.RecordingLogger
import bloop.tasks.ProjectHelpers

import org.junit.Test
import org.junit.Assert.{assertEquals, assertTrue}

class BasicNailgunSpec extends NailgunTest {

  @Test
  def unknownCommandTest(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      val command = "thatcommanddoesntexist"
      client.fail("thatcommanddoesntexist")
      val messages = logger.getMessages()
      assertTrue("Error was not reported in $messages",
                 messages.contains(("info", s"Command not found: $command")))
    }
  }

  @Test
  def aboutCommandTest(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      client.success("about")
      val messages = logger.getMessages()
      def contains(needle: String): Unit = {
        assertTrue(s"'$needle' not found in $messages", messages.exists(_._2.contains(needle)))
      }
      contains("Bloop-frontend version")
      contains("Zinc version")
      contains("Scala version")
      contains("maintained by")
      contains("Scala Center")
    }
  }

  @Test
  def projectsCommandTest(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
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
