package bloop.exec

import java.nio.file.{Files, Path}

import org.junit.Test
import org.junit.Assert.{assertEquals, assertNotEquals}
import org.junit.experimental.categories.Category

import bloop.logging.RecordingLogger
import bloop.io.AbsolutePath
import bloop.tasks.TestUtil
import bloop.tasks.TestUtil.withTemporaryDirectory

@Category(Array(classOf[bloop.FastTests]))
class ForkProcessSpec {

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
  val runnableProject = Map(
    TestUtil.RootProject -> Map("A.scala" -> ArtificialSources.`A.scala`))

  private def run(cwd: Path, args: Array[String])(op: (Int, List[(String, String)]) => Unit): Unit =
    TestUtil.checkAfterCleanCompilation(runnableProject, dependencies) { state =>
      val cwdPath = AbsolutePath(cwd)
      val project = TestUtil.getProject(TestUtil.RootProject, state)
      val env = JavaEnv.default
      val classpath = project.classpath
      val config = ForkProcess(env, classpath)
      val logger = new RecordingLogger
      val userEnv = TestUtil.runAndTestProperties
      val exitCode = config.runMain(cwdPath, s"$packageName.$mainClassName", args, logger, userEnv)
      val messages = logger.getMessages
      op(exitCode, messages)
    }

  @Test
  def canRun(): Unit = withTemporaryDirectory { tmp =>
    run(tmp, Array("foo", "bar", "baz")) {
      case (exitCode, messages) =>
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(("info", "Arguments: foo, bar, baz")))
        assert(messages.contains(("error", "testing stderr")))
    }
  }

  @Test
  def reportsExceptions(): Unit = withTemporaryDirectory { tmp =>
    run(tmp, Array("crash")) {
      case (exitCode, messages) =>
        assertNotEquals(0, exitCode.toLong)
        assert(messages.count(_._1 == "error") == 3)
    }
  }

  @Test
  def runReportsMissingCWD(): Unit = withTemporaryDirectory { tmp =>
    val nonExisting = "does-not-exist"
    val cwd = tmp.resolve(nonExisting)
    run(cwd, Array.empty) {
      case (exitCode, messages) =>
        val expected: ((String, String)) => Boolean = {
          case ("error", msg) => msg.contains(nonExisting)
          case _ => false
        }
        assertEquals(ForkProcess.EXIT_ERROR, exitCode.toLong)
        assert(messages.exists(expected), s"Couldn't find expected error messages in $messages")
    }
  }

  @Test
  def runHasCorrectWorkingDirectory(): Unit = withTemporaryDirectory { tmp =>
    val cwd = tmp.resolve("expected-dir")
    Files.createDirectory(cwd)
    run(cwd, Array.empty) {
      case (exitCode, messages) =>
        val expected = "info" -> s"CWD: ${cwd.toRealPath()}"
        assertEquals(0, exitCode.toLong)
        assert(messages.contains(expected), s"$messages didn't contain $expected.")
    }
  }

}
