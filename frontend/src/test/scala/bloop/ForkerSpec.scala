package bloop

import bloop.cli.ExitStatus
import bloop.dap.DebugSessionLogger
import bloop.data.JdkConfig
import bloop.exec.{Forker, JvmProcessForker}
import bloop.io.AbsolutePath
import bloop.logging.RecordingLogger
import bloop.util.TestUtil
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}
import java.util.concurrent.TimeUnit
import org.junit.Assert.{assertEquals, assertNotEquals}
import org.junit.Test
import org.junit.experimental.categories.Category
import scala.collection.mutable
import scala.concurrent.duration.Duration

@Category(Array(classOf[bloop.FastTests]))
class ForkerSpec {

  val packageName = "foo.bar"
  val mainClassName = "Main"

  object ArtificialSources {
    val `A.scala` =
      s"""package $packageName
         |object $mainClassName {
         |  def main(args: Array[String]): Unit = {
         |    if (args.contains("crash")) throw new Exception
         |    println(s"Arguments: $${args.mkString(", ")}")
         |    val cwd = new java.io.File(sys.props("user.dir")).getCanonicalPath
         |    println(s"CWD: $$cwd")
         |    System.err.println("testing stderr")
         |  }
         |}""".stripMargin
  }

  val dependencies = Map.empty[String, Set[String]]
  val runnableProject = Map(TestUtil.RootProject -> Map("A.scala" -> ArtificialSources.`A.scala`))

  private def run(
      cwd: AbsolutePath,
      args: Array[String],
      extraClasspath: Array[AbsolutePath] = Array.empty
  )(
      op: (Int, List[(String, String)]) => Unit
  ): Unit =
    TestUtil.checkAfterCleanCompilation(runnableProject, dependencies) { state =>
      val project = TestUtil.getProject(TestUtil.RootProject, state)
      val env = JdkConfig.default
      val classpath = project.fullClasspath(state.build.getDagFor(project), state.client)
      val config = JvmProcessForker(env, classpath)
      val logger = new RecordingLogger
      val opts = state.commonOptions.copy(env = TestUtil.runAndTestProperties)
      val mainClass = s"$packageName.$mainClassName"
      val wait = Duration.apply(25, TimeUnit.SECONDS)
      val exitCode = TestUtil.await(wait)(
        config.runMain(
          cwd,
          mainClass,
          args,
          skipJargs = false,
          logger.asVerbose,
          opts,
          extraClasspath
        )
      )
      val messages = logger.getMessages()
      op(exitCode, messages)
    }

  @Test
  def detectNewLines(): Unit = {
    val nl = System.lineSeparator()
    val remaining = new StringBuilder()
    val msg = ByteBuffer.wrap("   ".getBytes(StandardCharsets.UTF_8))
    val lines = linesFrom(msg, remaining)
    assert(lines.length == 0)
    assert(remaining.mkString == "   ")

    val msg2 = ByteBuffer.wrap(s"Hello${nl}World!$nl".getBytes(StandardCharsets.UTF_8))
    val lines2 = linesFrom(msg2, remaining)
    assert(lines2.length == 2)
    assert(lines2(0) == "   Hello")
    assert(lines2(1) == "World!")
    assert(remaining.isEmpty)

    val msg3 = ByteBuffer.wrap(s"${nl}${nl}asdf".getBytes(StandardCharsets.UTF_8))
    val lines3 = linesFrom(msg3, remaining)
    assert(lines3.length == 2)
    assert(lines3(0) == "")
    assert(lines3(1) == "")
    assert(remaining.mkString == "asdf")

    val msg4 = ByteBuffer.wrap(s"${nl}this is SPARTA${nl}".getBytes(StandardCharsets.UTF_8))
    val lines4 = linesFrom(msg4, remaining)
    assert(lines4.length == 2)
    assert(lines4(0) == "asdf")
    assert(lines4(1) == "this is SPARTA")
    assert(remaining.isEmpty)
  }

  @Test
  def detectJDINotification(): Unit = {
    val line = s"${DebugSessionLogger.JDINotificationPrefix} 123\n"
    val buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8))
    val remaining = new mutable.StringBuilder()
    val lines = linesFrom(buffer, remaining)
    assert(lines.sameElements(Array(line.stripLineEnd)))
    assert(remaining.isEmpty)
  }

  private def linesFrom(buffer: ByteBuffer, remaining: StringBuilder): Array[String] = {
    val lines = mutable.ArrayBuffer.empty[String]
    Forker.onEachLine(buffer, remaining)(lines += _)
    lines.toArray
  }

  @Test
  def canRun(): Unit = TestUtil.withinWorkspace { tmp =>
    run(tmp, Array("foo", "bar", "baz")) {
      case (exitCode, messages) =>
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(("info", "Arguments: foo, bar, baz")))
        assert(messages.contains(("error", "testing stderr")))
    }
  }

  @Test
  def canHandleLongClasspaths(): Unit = TestUtil.withinWorkspace { tmp =>
    TestUtil.withinWorkspace { tmpJarDir =>
      val longCp = (1 to 3000).map { i =>
        val tmpFile = tmpJarDir.resolve(s"forkerspec-temp-$i.jar").underlying
        AbsolutePath(Files.createFile(tmpFile))
      }.toArray

      val charLimitMsg =
        s"""|Supplied command to fork exceeds character limit of 30000
            |Creating a temporary MANIFEST jar for classpath entries
            |""".stripMargin

      val cleanupPrefix = "Cleaning up temporary MANIFEST jar: "

      def isCleanupMessage(message: (String, String)): Boolean = message match {
        case ("debug", msg) if msg.startsWith(cleanupPrefix) =>
          true
        case _ =>
          false
      }

      run(tmp, Array("foo", "bar", "baz"), longCp) {
        case (exitCode, messages) =>
          assertEquals(0, exitCode.toLong)
          assert(messages.contains(("debug", charLimitMsg)))

          val cleanupMessage = messages.filter(isCleanupMessage)
          assertEquals(cleanupMessage.size.toLong, 1L)

          val tempManifestJar = Paths.get(cleanupMessage.head._2.stripPrefix(cleanupPrefix))
          assert(tempManifestJar.isAbsolute)
          assert(Files.notExists(tempManifestJar))

          assert(messages.contains(("info", "Arguments: foo, bar, baz")))
          assert(messages.contains(("error", "testing stderr")))
      }
    }
  }

  @Test
  def reportsExceptions(): Unit = TestUtil.withinWorkspace { tmp =>
    run(tmp, Array("crash")) {
      case (exitCode, messages) =>
        assertNotEquals(0, exitCode.toLong)
        assert(messages.count(_._1 == "error") == 3)
    }
  }

  @Test
  def runReportsMissingCWD(): Unit = TestUtil.withinWorkspace { tmp =>
    val nonExisting = "does-not-exist"
    val cwd = tmp.resolve(nonExisting)
    run(cwd, Array.empty) {
      case (exitCode, messages) =>
        val expected: ((String, String)) => Boolean = {
          case ("error", msg) => msg.contains(nonExisting)
          case _ => false
        }
        val exitStatus = Forker.exitStatus(exitCode)
        assertEquals(ExitStatus.RunError, exitStatus)
        assert(messages.exists(expected), s"Could not find expected error messages in $messages")
    }
  }

  @Test
  def runHasCorrectWorkingDirectory(): Unit = TestUtil.withinWorkspace { tmp =>
    val cwd = tmp.resolve("expected-dir")
    Files.createDirectory(cwd.underlying)
    run(cwd, Array.empty) {
      case (exitCode, messages) =>
        val expected = "info" -> s"CWD: ${cwd.underlying.toRealPath()}"
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(expected), s"$messages did not contain $expected")
    }
  }
}
