package bloop.exec

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import org.junit.Test
import org.junit.Assert.{assertEquals, assertNotEquals}
import org.junit.experimental.categories.Category
import bloop.cli.ExitStatus
import bloop.logging.RecordingLogger
import bloop.io.AbsolutePath
import bloop.util.TestUtil

import scala.concurrent.duration.Duration

@Category(Array(classOf[bloop.FastTests]))
class ForkerSpec {

  val packageName = "foo.bar"
  val mainClassName = "Main"

  object ArtificialSources {
    val `A.scala` = s"""package $packageName
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

  private def run(cwd: Path, args: Array[String])(op: (Int, List[(String, String)]) => Unit): Unit =
    TestUtil.checkAfterCleanCompilation(runnableProject, dependencies) { state =>
      val cwdPath = AbsolutePath(cwd)
      val project = TestUtil.getProject(TestUtil.RootProject, state)
      val env = JavaEnv.default
      val classpath = project.fullClasspathFor(state.build.getDagFor(project))
      val config = Forker(env, classpath)
      val logger = new RecordingLogger
      val opts = state.commonOptions.copy(env = TestUtil.runAndTestProperties)
      val mainClass = s"$packageName.$mainClassName"
      try {
        val wait = Duration.apply(25, TimeUnit.SECONDS)
        val exitCode =
          TestUtil.await(wait)(
            config.runMain(cwdPath, mainClass, args, false, logger.asVerbose, opts))
        val messages = logger.getMessages()
        op(exitCode, messages)
      } finally {
        logger.dump()
      }
    }

  @Test
  def detectNewLines(): Unit = {
    val nl = System.lineSeparator()
    val remaining = new StringBuilder()
    val msg = ByteBuffer.wrap("   ".getBytes(StandardCharsets.UTF_8))
    val lines = Forker.linesFrom(msg, remaining)
    assert(lines.length == 0)
    assert(remaining.mkString == "   ")

    val msg2 = ByteBuffer.wrap(s"Hello${nl}World!$nl".getBytes(StandardCharsets.UTF_8))
    val lines2 = Forker.linesFrom(msg2, remaining)
    assert(lines2.length == 2)
    assert(lines2(0) == "   Hello")
    assert(lines2(1) == "World!")
    assert(remaining.mkString.isEmpty)

    val msg3 = ByteBuffer.wrap(s"${nl}${nl}asdf".getBytes(StandardCharsets.UTF_8))
    val lines3 = Forker.linesFrom(msg3, remaining)
    assert(lines3.length == 2)
    assert(lines3(0) == "")
    assert(lines3(1) == "")
    assert(remaining.mkString == "asdf")

    val msg4 = ByteBuffer.wrap(s"${nl}this is SPARTA${nl}".getBytes(StandardCharsets.UTF_8))
    val lines4 = Forker.linesFrom(msg4, remaining)
    assert(lines4.length == 2)
    assert(lines4(0) == "asdf")
    assert(lines4(1) == "this is SPARTA")
  }

  @Test
  def canRun(): Unit = TestUtil.withTemporaryDirectory { tmp =>
    run(tmp, Array("foo", "bar", "baz")) {
      case (exitCode, messages) =>
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(("info", "Arguments: foo, bar, baz")))
        assert(messages.contains(("error", "testing stderr")))
    }
  }

  @Test
  def reportsExceptions(): Unit = TestUtil.withTemporaryDirectory { tmp =>
    run(tmp, Array("crash")) {
      case (exitCode, messages) =>
        assertNotEquals(0, exitCode.toLong)
        assert(messages.count(_._1 == "error") == 3)
    }
  }

  @Test
  def runReportsMissingCWD(): Unit = TestUtil.withTemporaryDirectory { tmp =>
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
  def runHasCorrectWorkingDirectory(): Unit = TestUtil.withTemporaryDirectory { tmp =>
    val cwd = tmp.resolve("expected-dir")
    Files.createDirectory(cwd)
    run(cwd, Array.empty) {
      case (exitCode, messages) =>
        val expected = "info" -> s"CWD: ${cwd.toRealPath()}"
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(expected), s"$messages did not contain $expected")
    }
  }

}
