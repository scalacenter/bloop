package bloop.nailgun

import java.util.concurrent.TimeUnit

import scala.Console.{GREEN, RESET}
import org.junit.Test
import org.junit.Assert.{assertEquals, assertTrue}
import bloop.logging.RecordingLogger

class BasicNailgunSpec extends NailgunTest {

  val out = System.out
  val err = System.err

  @Test
  def unknownCommandTest(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      val command = "thatcommanddoesntexist"
      client.fail(command)
      val messages = logger.getMessages()
      assertTrue(s"Error was not reported in $messages",
                 messages.contains(("info", s"Command not found: $command")))
    }
  }

  @Test
  def testHelpCommand(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      client.success("help")
      val messages = logger.getMessages()
      def contains(needle: String): Unit = {
        assertTrue(s"'$needle not found in $messages'", messages.exists(_._2.contains(needle)))
      }
      contains("Usage:")
      contains("Available commands:")
    }
  }

  @Test
  def testAboutCommand(): Unit = {
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
  def testProjectsCommand(): Unit = {
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

  @Test
  def testCompileCommand(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      client.success("clean", "with-resources")
      client.success("compile", "with-resources")
      client.success("clean", "-p", "with-resources")
      client.success("compile", "-p", "with-resources")
      val messages = logger.getMessages()
      val needle = "Compiling"

      assertTrue(s"${messages.mkString("\n")} didn't contain '$needle'", messages.exists {
        case ("info", msg) => msg.contains(needle)
        case _ => false
      })
    }
  }

  @Test
  def testWatchCompileCommand(): Unit = {
    var rlogger: RecordingLogger = null
    withServerInProject("with-resources") { (logger, client) =>
      client.success("clean", "with-resources")
      val fileWatchingProcess = client.issueAsProcess("compile", "-w", "with-resources")
      fileWatchingProcess.waitFor(4, TimeUnit.SECONDS)
      fileWatchingProcess.destroyForcibly()

      // Repeat the whole process again.
      client.success("clean", "with-resources")
      val fileWatchingProcess2 = client.issueAsProcess("compile", "-w", "with-resources")
      fileWatchingProcess2.waitFor(4, TimeUnit.SECONDS)
      fileWatchingProcess2.destroyForcibly()

      // Ugly, but necessary for the sake of testing.
      rlogger = logger
    }

    // We check here the logs because until 'exit' the server doesn't flush everything
    val serverInfos = rlogger.getMessages().filter(_._1 == "server-info").map(_._2)
    val timesCancelling = serverInfos.count(_.contains("Cancelling tasks..."))
    val timesCancelled = serverInfos.count(
      _ == "File watching on 'with-resources' and dependent projects has been successfully cancelled.")

    assertTrue(s"File watching cancellation happened $timesCancelled only!", timesCancelled == 2)
    assertTrue(s"Bloop registered task cancellation only $timesCancelling", timesCancelling == 2)
  }

  @Test
  def testRunCommand(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      client.success("run", "with-resources")
      client.success("run", "-p", "with-resources")
      val messages = logger.getMessages()
      val needle = ("info", "OK")
      assertTrue(s"${messages.mkString("\n")} didn't contain '$needle'", messages.contains(needle))
    }
  }

  @Test
  def testTestCommand(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      client.success("test", "with-resources")
      client.success("test", "-p", "with-resources")
      val messages = logger.getMessages()
      val needle = "- should be found"
      assertTrue(s"${messages.mkString("\n")} didn't contain '$needle'", messages.exists {
        case ("info", msg) => msg.contains(needle)
        case _ => false
      })
    }

  }

  @Test
  def testCleanCommand(): Unit = {
    withServerInProject("with-resources") { (logger, client) =>
      client.success("clean", "-p", "with-resources")
      client.success("compile", "-p", "with-resources")
      client.success("clean", "-p", "with-resources")
      client.success("compile", "-p", "with-resources")
      client.success("clean", "with-resources")
      client.success("compile", "with-resources")
      client.success("clean", "with-resources")
      client.success("compile", "with-resources")
      val messages = logger.getMessages()
      val needle = "Compiling"
      val matches = messages.count {
        case ("info", msg) => msg.contains(needle)
        case _ => false
      }
      assertEquals(s"${messages.mkString("\n")} should contain four times '$needle'",
                   4,
                   matches.toLong)
    }
  }

  @Test
  def testSeveralConcurrentClients(): Unit = {
    withServerInProject("with-resources") { (logger1, client1) =>
      val debugPrefix = s"${RESET}${GREEN}[D]${RESET} "
      val logger2 = new RecordingLogger
      val client2 = client1.copy(log = logger2)

      val thread1 = new Thread {
        override def run() = {
          (1 to 3).foreach { _ =>
            client1.success("clean", "with-resources-test")
            client1.success("compile", "with-resources-test", "--verbose")
          }
        }
      }

      val thread2 = new Thread {
        override def run() = {
          try while (true) client2.success("projects")
          catch { case _: InterruptedException => () }
        }
      }

      thread1.start()
      Thread.sleep(1000)
      thread2.start()

      thread1.join()
      thread2.interrupt()

      val msgs1 = logger1.getMessages.map(_._2)
      val msgs2 = logger2.getMessages.map(_._2)

      assertTrue("`logger1` received messages of `logger2`",
                 !msgs1.exists(_.startsWith("Projects loaded from")))
      assertEquals("`logger` didn't receive verbose messages",
                   3,
                   msgs1.count(_.startsWith(s"${debugPrefix}Elapsed:")).toLong)
      assertTrue("`logger2` received messages of `logger1`",
                 !msgs2.exists(_.startsWith("Compiling")))
      assertTrue("`logger2` received verbose messages of `logger1`",
                 !msgs2.exists(_.startsWith(debugPrefix)))
    }
  }

}
